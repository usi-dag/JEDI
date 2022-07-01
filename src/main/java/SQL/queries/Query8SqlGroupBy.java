package SQL.queries;

import SQL.dataset.*;
import SQL.datatypes.Date;
import SQL.sql_utils.GroupByCollector;
import SQL.sql_utils.SQLCollectors;
import SQL.sql_utils.SQLStreamUtils;

import java.util.Comparator;
import java.util.List;

public class Query8SqlGroupBy implements Query {
    @Override
    public List<?> execute(TPCHStreamDB db) {
        Date min = new Date("1995-01-01");
        Date max = new Date("1996-12-31");
        // part and lineItem join
        var partLineItemJoin = SQLStreamUtils.streamJoin(
                db.part().filter(p -> p.p_type().equals("ECONOMY ANODIZED STEEL")),
                PartRow::p_partkey,
                db.lineitem(),
                LineItemRow::l_partkey,
                (p, l) -> l
        );

        // lineItem orders join
        var lineItemOrdersJoin = SQLStreamUtils.streamJoin(
                partLineItemJoin,
                LineItemRow::l_orderkey,
                db.orders().filter(o -> o.o_orderdate().fromTo(min, max)),
                OrdersRow::o_orderkey,
                (l, o) -> new Query8.LineItemOrder(o.o_orderdate(), o.o_custkey(), l.l_extendedprice(), l.l_discount(), l.l_suppkey())
        );


        // LineItemOrder and Customer join
        var lineItemOrderCustomerJoin = SQLStreamUtils.streamJoin(
                lineItemOrdersJoin,
                Query8.LineItemOrder::o_custkey,
                db.customer(),
                CustomerRow::c_custkey,
                (lo, c) -> new Query8.LineItemOrderCustomer(lo.o_orderdate(), c.c_nationkey(), lo.l_extendedprice(), lo.l_discount(), lo.l_suppkey())
        );


        // region netion join (left part query plan)
        var regionNationJoin = SQLStreamUtils.streamJoin(
                db.region().filter(r -> r.r_name().equals("AMERICA")),
                RegionRow::r_regionkey,
                db.nation(),
                NationRow::n_regionkey,
                (r, n) -> n
        );


        // regionNation and lineItemOrderCustomer join
        var regionNationAndLineItemOrderCustomerJoin = SQLStreamUtils.streamJoin(
                regionNationJoin,
                NationRow::n_nationkey,
                lineItemOrderCustomerJoin,
                Query8.LineItemOrderCustomer::c_nationkey,
                (n, loc) -> loc
        );


        // join regionNationAndLineItemOrderCustomerJoin and supplier
        var regionNationAndLineItemOrderCustomerSupplierJoin = SQLStreamUtils.streamJoin(
                regionNationAndLineItemOrderCustomerJoin,
                Query8.LineItemOrderCustomer::l_suppkey,
                db.supplier(),
                SupplierRow::s_suppkey,
                (loc, s) -> new Query8.LineItemOrderCustomerSupp(loc.o_orderdate(), s.s_nationkey(), loc.l_extendedprice(), loc.l_discount())
        );

        Comparator<Query8Imperative.Result> comparator = Comparator.comparing(r -> r.o_years);

        // last join
        return SQLStreamUtils.streamJoin(
                        db.nation(),
                        NationRow::n_nationkey,
                        regionNationAndLineItemOrderCustomerSupplierJoin,
                        Query8.LineItemOrderCustomerSupp::s_nationkey,
                        (n, loc) -> new Query8.Last(loc.o_orderdate(), loc.l_extendedprice(), loc.l_discount(), n.n_name())
                ).collect(
                        SQLCollectors.groupingBy(o -> o.o_orderdate().getYears(), GroupByCollector.of(
                                (Query8.Last loc) -> new Query8Imperative.Result(loc.o_orderdate().getYears()),
                                (a, b) -> a.aggregate(b.l_extendedprice(), b.l_discount(), b.nation()),
                                (a, b) -> null,
                                Query8Imperative.Result::finalizer
                        ))
                ).values()
                .stream()
                .sorted(comparator)
                .toList();
    }
}