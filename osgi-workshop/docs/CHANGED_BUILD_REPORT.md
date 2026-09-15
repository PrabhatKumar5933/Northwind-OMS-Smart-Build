# Changed-Modules Test Report

Test scenario from the supplied assessment README: source changes in three functional modules:

- `catalog/plugins/com.northwind.oms.core/src/...`
- `orders/plugins/com.northwind.oms.gateway/src/...`
- `payment/plugins/com.northwind.oms.payment/src/...`

The Java smart-build tool was run in analysis-only mode with representative source-file paths. Maven was intentionally not invoked for this report because the execution environment used to prepare the submission does not have Maven installed.

## Detected changes

- `com.northwind.oms.core`
- `com.northwind.oms.gateway`
- `com.northwind.oms.payment`

## Selected rebuild modules

The transitive downstream dependency analysis selected 14 modules/features:

1. `com.northwind.oms.core`
2. `com.northwind.oms.inventory`
3. `com.northwind.oms.catalog.feature`
4. `com.northwind.oms.payment`
5. `com.northwind.oms.payment.feature`
6. `com.northwind.oms.pricing`
7. `com.northwind.oms.gateway`
8. `com.northwind.oms.orders.feature`
9. `com.northwind.oms.reporting`
10. `com.northwind.oms.reporting.feature`
11. `com.northwind.oms.shipping`
12. `com.northwind.oms.notification`
13. `com.northwind.oms.notification.feature`
14. `com.northwind.oms.shipping.feature`

Unselected modules include customer, security, third-party SLF4J and their features.

## Build order

The Java tool performs a topological sort. Graph direction is `A -> B`, meaning B depends on A, so A is built first.

1. `com.northwind.oms.core`
2. `com.northwind.oms.inventory`
3. `com.northwind.oms.catalog.feature`
4. `com.northwind.oms.payment`
5. `com.northwind.oms.payment.feature`
6. `com.northwind.oms.pricing`
7. `com.northwind.oms.gateway`
8. `com.northwind.oms.orders.feature`
9. `com.northwind.oms.reporting`
10. `com.northwind.oms.reporting.feature`
11. `com.northwind.oms.shipping`
12. `com.northwind.oms.notification`
13. `com.northwind.oms.notification.feature`
14. `com.northwind.oms.shipping.feature`

## Runtime logging

The same information is printed by `build.sh --changed`: detected changes, dependency paths, selected modules, skipped modules and numbered build order. `--dot` additionally writes the dependency graph.
