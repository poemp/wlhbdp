[sql]
select
    supp_nation,
    cust_nation,
    l_year,
    sum(volume) as revenue
from
    (
        select
            n1.n_name as supp_nation,
            n2.n_name as cust_nation,
            extract(year from l_shipdate) as l_year,
            l_extendedprice * (1 - l_discount) as volume
        from
            supplier,
            lineitem,
            orders,
            customer,
            nation n1,
            nation n2
        where
                s_suppkey = l_suppkey
          and o_orderkey = l_orderkey
          and c_custkey = o_custkey
          and s_nationkey = n1.n_nationkey
          and c_nationkey = n2.n_nationkey
          and (
                (n1.n_name = 'CANADA' and n2.n_name = 'IRAN')
                or (n1.n_name = 'IRAN' and n2.n_name = 'CANADA')
            )
          and l_shipdate between date '1995-01-01' and date '1996-12-31'
    ) as shipping
group by
    supp_nation,
    cust_nation,
    l_year
order by
    supp_nation,
    cust_nation,
    l_year ;
[result]
TOP-N (order by [[42: n_name ASC NULLS FIRST, 46: n_name ASC NULLS FIRST, 49: year ASC NULLS FIRST]])
    TOP-N (order by [[42: n_name ASC NULLS FIRST, 46: n_name ASC NULLS FIRST, 49: year ASC NULLS FIRST]])
        AGGREGATE ([GLOBAL] aggregate [{368: sum=sum(368: sum)}] group by [[66: n_name1, 67: n_name2, 68: l_shipyear]] having [null]
            EXCHANGE SHUFFLE[66, 67, 68]
                AGGREGATE ([LOCAL] aggregate [{368: sum=sum(69: sum_saleprice)}] group by [[66: n_name1, 67: n_name2, 68: l_shipyear]] having [null]
                    SCAN (mv[lineitem_mv_agg_mv2] columns[65: l_shipdate, 66: n_name1, 67: n_name2, 68: l_shipyear, 69: sum_saleprice] predicate[65: l_shipdate >= 1996-12-31 AND 65: l_shipdate <= 1995-01-01 AND 66: n_name1 = CANADA AND 67: n_name2 = IRAN OR 66: n_name1 = IRAN AND 67: n_name2 = CANADA])
[end]

