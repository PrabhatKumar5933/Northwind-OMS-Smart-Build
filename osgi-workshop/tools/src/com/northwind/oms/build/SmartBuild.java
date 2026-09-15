package com.northwind.oms.build;

import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Smart build orchestrator for the Northwind OSGi/Tycho sample.
 *
 * Direction used by the graph: A -> B means "B depends on A".
 * Therefore A must be built before B.
 */
public final class SmartBuild {
    static final String ROOT = ".";
    static final Path WORKSHOP = Paths.get(".").toAbsolutePath().normalize();
    static final Pattern CONTINUATION = Pattern.compile("^\\s+.*$");

    static final class Module {
        final String id;
        final Path path;
        final String type;
        final Set<String> dependencies = new LinkedHashSet<>();
        final Set<String> dependents = new LinkedHashSet<>();
        Module(String id, Path path, String type) { this.id = id; this.path = path; this.type = type; }
        @Override public String toString() { return id; }
    }

    public static void main(String[] args) throws Exception {
        boolean all = has(args, "--all");
        boolean changedMode = has(args, "--changed");
        boolean graph = has(args, "--graph");
        boolean noBuild = has(args, "--no-build");
        boolean dot = has(args, "--dot");
        List<String> explicit = valueList(args, "--files");

        if (!all && !changedMode && explicit.isEmpty() && !graph) {
            usage();
            System.exit(2);
        }

        Map<String, Module> modules = discover();
        buildReverseEdges(modules);

        if (dot) {
            Path out = WORKSHOP.resolve("docs").resolve(all ? "full-build-graph.dot" : "changed-build-graph.dot");
            writeDot(modules, all ? modules.keySet() : selectedForExplicit(modules, explicit), out, explicit);
            System.out.println("[SMART-BUILD] DOT graph written: " + out);
        }

        if (graph || all) {
            if (graph && !all && explicit.isEmpty() && !changedMode) {
                printGraph(modules, modules.keySet(), List.of());
                return;
            }
            printGraph(modules, all ? modules.keySet() : selectedForExplicit(modules, explicit), explicit);
        }

        if (all) {
            List<String> order = topo(modules, modules.keySet());
            System.out.println("\n[SMART-BUILD] SCENARIO 1 - FULL BUILD");
            printOrder(order, modules);
            if (!noBuild) runMaven("clean", "verify");
            return;
        }

        Set<String> changed = new LinkedHashSet<>();
        if (!explicit.isEmpty()) {
            for (String f : explicit) {
                String id = moduleForFile(modules, normalize(f));
                if (id != null) changed.add(id);
            }
        } else {
            for (String f : gitChangedFiles()) {
                String id = moduleForFile(modules, normalize(f));
                if (id != null) changed.add(id);
            }
        }

        System.out.println("\n[SMART-BUILD] SCENARIO 2 - CHANGED MODULES");
        System.out.println("[SMART-BUILD] Detected changed modules:");
        changed.forEach(x -> System.out.println("  [CHANGED] " + x + " -> " + modules.get(x).path));
        if (changed.isEmpty()) {
            System.out.println("[SMART-BUILD] No changed files map to a product module. Nothing to rebuild.");
            return;
        }

        Set<String> impactedModules = impacted(modules, changed);
        Set<String> selected = selectedForBuild(modules, impactedModules);
        System.out.println("\n[SMART-BUILD] Impact analysis (changed module -> downstream dependents):");
        for (String c : changed) {
            System.out.println("  " + c);
            printPaths(modules, c, selected);
        }

        List<String> order = topo(modules, selected);
        System.out.println("\n[SMART-BUILD] Selected modules: " + selected.size());
        for (String id : order) System.out.println("  [REBUILD] " + id + " -> " + modules.get(id).path);
        System.out.println("\n[SMART-BUILD] Modules skipped:");
        modules.keySet().stream().filter(x -> !selected.contains(x)).forEach(x -> System.out.println("  [SKIP] " + x));
        printOrder(order, modules);

        if (!noBuild) {
            List<String> mvn = new ArrayList<>(List.of("verify", "-pl", String.join(",", selectedPaths(modules, selected)), "-am"));
            runMaven(mvn.toArray(String[]::new));
        }
    }

    static Map<String, Module> discover() throws Exception {
        Map<String, Module> result = new LinkedHashMap<>();
        Files.walk(WORKSHOP).filter(p -> p.getFileName().toString().equals("MANIFEST.MF")).forEach(p -> {
            try {
                Map<String,String> h = manifestHeaders(p);
                String bsn = firstName(h.get("Bundle-SymbolicName"));
                if (bsn == null) return;
                Module m = new Module(bsn, relative(p.getParent().getParent()), "bundle");
                Set<String> imported = names(h.get("Import-Package"));
                Set<String> required = names(h.get("Require-Bundle"));
                m.dependencies.addAll(required);
                result.put(bsn, m);
                // Package imports are resolved after all exports are known.
                m.dependencies.addAll(imported.stream().map(x -> "@PKG:" + x).toList());
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Map<String,String> packageOwners = new HashMap<>();
        for (Module m : result.values()) {
            Path mf = WORKSHOP.resolve(m.path).resolve("META-INF/MANIFEST.MF");
            Map<String,String> h = manifestHeaders(mf);
            for (String p : names(h.get("Export-Package"))) packageOwners.put(p, m.id);
        }
        for (Module m : result.values()) {
            Set<String> resolved = new LinkedHashSet<>();
            for (String d : m.dependencies) {
                if (d.startsWith("@PKG:")) {
                    String owner = packageOwners.get(d.substring(5));
                    if (owner != null && !owner.equals(m.id)) resolved.add(owner);
                } else if (result.containsKey(d) && !d.equals(m.id)) resolved.add(d);
            }
            m.dependencies.clear(); m.dependencies.addAll(resolved);
        }

        Files.walk(WORKSHOP).filter(p -> p.getFileName().toString().equals("feature.xml")).forEach(p -> {
            try {
                Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(p.toFile());
                Element feature = doc.getDocumentElement();
                String id = feature.getAttribute("id");
                if (id == null || id.isBlank()) return;
                Module fm = new Module(id, relative(p.getParent()), "feature");
                NodeList plugins = feature.getElementsByTagName("plugin");
                for (int i=0;i<plugins.getLength();i++) {
                    Element e=(Element)plugins.item(i); String x=e.getAttribute("id");
                    if(result.containsKey(x)) fm.dependencies.add(x);
                }
                NodeList req = feature.getElementsByTagName("import");
                for (int i=0;i<req.getLength();i++) {
                    Element e=(Element)req.item(i); String x=e.getAttribute("feature");
                    if(result.containsKey(x)) fm.dependencies.add(x);
                }
                result.put(id, fm);
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        return result;
    }

    static Path relative(Path p) { return WORKSHOP.relativize(p.toAbsolutePath().normalize()); }

    static Map<String,String> manifestHeaders(Path p) throws IOException {
        List<String> lines=Files.readAllLines(p, StandardCharsets.UTF_8); Map<String,String> h=new LinkedHashMap<>();
        String key=null;
        for(String line:lines){
            if(line.isBlank()) continue;
            if(line.startsWith(" ") && key!=null) h.put(key,h.get(key)+line.substring(1));
            else { int c=line.indexOf(':'); if(c>0){ key=line.substring(0,c).trim(); h.put(key,line.substring(c+1).trim()); }}
        }
        return h;
    }

    static String firstName(String clause){ if(clause==null) return null; int c=findUnquoted(clause, ','); String x=(c<0?clause:clause.substring(0,c)); int semi=findUnquoted(x, ';'); if(semi>=0)x=x.substring(0,semi); return x.trim(); }

    static Set<String> names(String clause){
        Set<String> out=new LinkedHashSet<>(); if(clause==null) return out;
        for(String part:splitClauses(clause)){ int semi=findUnquoted(part,';'); String name=(semi<0?part:part.substring(0,semi)).trim(); if(!name.isBlank()) out.add(name); }
        return out;
    }

    static List<String> splitClauses(String s){
        List<String> out=new ArrayList<>(); StringBuilder b=new StringBuilder(); boolean quote=false,esc=false;
        for(char ch:s.toCharArray()){
            if(esc){b.append(ch);esc=false;continue;} if(ch=='\\'){b.append(ch);esc=true;continue;} if(ch=='"'){b.append(ch);quote=!quote;continue;}
            if(ch==','&&!quote){out.add(b.toString());b.setLength(0);} else b.append(ch);
        } out.add(b.toString()); return out;
    }
    static int findUnquoted(String s,char target){ boolean q=false,e=false; for(int i=0;i<s.length();i++){char c=s.charAt(i); if(e){e=false;continue;} if(c=='\\'){e=true;continue;} if(c=='"'){q=!q;continue;} if(c==target&&!q)return i;} return -1; }

    static void buildReverseEdges(Map<String,Module> m){ for(Module x:m.values()) for(String d:x.dependencies) if(m.containsKey(d)) m.get(d).dependents.add(x.id); }

    static Set<String> impacted(Map<String,Module> m, Set<String> changed){ Set<String> s=new LinkedHashSet<>(changed); Deque<String> q=new ArrayDeque<>(changed); while(!q.isEmpty()){String x=q.remove(); for(String d:m.get(x).dependents) if(s.add(d))q.add(d);} return s; }

    static List<String> topo(Map<String,Module> m, Collection<String> ids){
        Set<String> set=new LinkedHashSet<>(ids); Map<String,Integer> indeg=new HashMap<>();
        for(String x:set) indeg.put(x,0); for(String x:set) for(String d:m.get(x).dependencies) if(set.contains(d)) indeg.put(x,indeg.get(x)+1);
        PriorityQueue<String> q=new PriorityQueue<>(); indeg.forEach((k,v)->{if(v==0)q.add(k);}); List<String> out=new ArrayList<>();
        while(!q.isEmpty()){String x=q.poll();out.add(x);for(String d:m.get(x).dependents)if(set.contains(d)&&indeg.merge(d,-1,Integer::sum)==0)q.add(d);}
        if(out.size()!=set.size()) throw new IllegalStateException("Dependency cycle detected in selected modules"); return out;
    }

    static void printPaths(Map<String,Module> m,String start,Set<String> selected){
        for(String d:m.get(start).dependents) if(selected.contains(d)) printPathsRec(m,start,d,new LinkedHashSet<>(List.of(start)),selected);
    }
    static void printPathsRec(Map<String,Module> m,String start,String cur,LinkedHashSet<String> path,Set<String> selected){
        path.add(cur); System.out.println("    " + String.join(" -> ",path));
        if(path.size()>1) for(String d:m.get(cur).dependents) if(selected.contains(d)&&!path.contains(d)) printPathsRec(m,start,d,new LinkedHashSet<>(path),selected);
    }

    static void printGraph(Map<String,Module> m,Collection<String> ids,List<String> changed){ Set<String>s=new LinkedHashSet<>(ids); System.out.println("\n[SMART-BUILD] PRODUCT DEPENDENCY GRAPH (A -> B means B depends on A)"); for(String x:s){String mark=changed.contains(x)?" [CHANGED]":""; System.out.println("  "+x+mark+" -> "+m.get(x).dependents.stream().filter(s::contains).toList());}}

    static void printOrder(List<String> order,Map<String,Module> m){System.out.println("\n[SMART-BUILD] BUILD ORDER:"); for(int i=0;i<order.size();i++)System.out.printf("  %02d. %s (%s)%n",i+1,order.get(i),m.get(order.get(i)).type);}

static Set<String> selectedForBuild(Map<String,Module> m, Set<String> changed) {
    Set<String> selected = impacted(m, changed);
    Deque<String> queue = new ArrayDeque<>(selected);
    while (!queue.isEmpty()) {
        String id = queue.poll();
        Module mod = m.get(id);
        if (mod == null) continue;
        for (String dependency : mod.dependencies) {
            if (m.containsKey(dependency) && selected.add(dependency)) {
                queue.add(dependency);
            }
        }
    }
    return selected;
}

    static Set<String> selectedForExplicit(Map<String,Module> m,List<String> files){
    Set<String> changed = new LinkedHashSet<>();

    for(String f:files){
        String x=moduleForFile(m,normalize(f));
        if(x!=null) changed.add(x);
    }

    // First find all downstream modules impacted by the changes.
    Set<String> selected = impacted(m,changed);
        return selected;
}

    static String moduleForFile(Map<String,Module> m,String file){
    String best=null;
    int len=-1;
    for(Module x:m.values()){
        String p=x.path.toString().replace('\\','/')+"/";
        if(file.equals(p.substring(0,p.length()-1)) || file.startsWith(p)){
            if(p.length()>len){
                best=x.id;
                len=p.length();
            }
        }
    }
    return best;
}

static String normalize(String f){
    String x=f.replace('\\','/');
    if(x.startsWith("./")) x=x.substring(2);
    if(x.startsWith("osgi-workshop/")) x=x.substring("osgi-workshop/".length());
    return x;
}

static List<String> gitChangedFiles() throws Exception { Process p=new ProcessBuilder("git","diff","--name-only","HEAD").directory(WORKSHOP.toFile()).redirectErrorStream(true).start(); List<String>out=new ArrayList<>(readLines(p)); p.waitFor(); Process u=new ProcessBuilder("git","ls-files","--others","--exclude-standard").directory(WORKSHOP.toFile()).redirectErrorStream(true).start();out.addAll(readLines(u));u.waitFor();return out; }
    static List<String> readLines(Process p)throws IOException{try(BufferedReader r=p.inputReader()){return r.lines().toList();}}

    static List<String> selectedPaths(Map<String,Module>m,Set<String>s){List<String>o=new ArrayList<>();for(String x:s)o.add(m.get(x).path.toString().replace('\\','/'));return o;}

    static void writeDot(Map<String,Module> m, Collection<String> ids, Path out, List<String> changed) throws IOException {
        Set<String> selected = new LinkedHashSet<>(ids);
        StringBuilder b = new StringBuilder();
        char q = 34;
        b.append("digraph northwind {\n");
        b.append("  rankdir=LR;\n");
        b.append("  node [shape=box];\n");
        for (String x : selected) {
            String label = x + (changed.contains(x) ? "\n[CHANGED]" : "");
            b.append("  ").append(q).append(x).append(q).append(" [label=").append(q).append(label).append(q).append("];\n");
        }
        for (String x : selected) {
            for (String d : m.get(x).dependents) {
                if (selected.contains(d)) {
                    b.append("  ").append(q).append(x).append(q).append(" -> ").append(q).append(d).append(q).append(";\n");
                }
            }
        }
        b.append("}\n");
        Files.createDirectories(out.getParent());
        Files.writeString(out, b.toString(), StandardCharsets.UTF_8);
    }

    static void runMaven(String... args)throws Exception{List<String>cmd=new ArrayList<>();cmd.add("mvn");cmd.add("-B");cmd.addAll(List.of(args));System.out.println("\n[SMART-BUILD] Executing: "+String.join(" ",cmd));Process p=new ProcessBuilder(cmd).directory(WORKSHOP.toFile()).inheritIO().start();int rc=p.waitFor();if(rc!=0)throw new RuntimeException("Maven build failed with exit code "+rc);}
    static boolean has(String[]a,String x){return Arrays.asList(a).contains(x);} static List<String> valueList(String[]a,String key){for(int i=0;i<a.length;i++)if(a[i].equals(key)){List<String>r=new ArrayList<>();for(int j=i+1;j<a.length&&!a[j].startsWith("--");j++)r.addAll(Arrays.asList(a[j].split(",")));return r;}return List.of();}
    static void usage(){System.out.println("Usage: java ...SmartBuild --all [--no-build] [--dot] | --changed [--files <file1,file2,...>] [--no-build] [--dot] | --graph");}
}
