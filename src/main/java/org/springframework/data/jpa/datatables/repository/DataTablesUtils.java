package org.springframework.data.jpa.datatables.repository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Expression;
import javax.persistence.criteria.Fetch;
import javax.persistence.criteria.From;
import javax.persistence.criteria.JoinType;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import javax.persistence.metamodel.Attribute.PersistentAttributeType;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.domain.Sort.Order;
import org.springframework.data.jpa.datatables.mapping.DataTablesInput;
import org.springframework.data.jpa.datatables.parameter.ColumnParameter;
import org.springframework.data.jpa.datatables.parameter.OrderParameter;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import com.mysema.query.BooleanBuilder;
import com.mysema.query.support.Expressions;
import com.mysema.query.types.Ops;
import com.mysema.query.types.expr.StringExpression;
import com.mysema.query.types.path.PathBuilder;

public class DataTablesUtils {

  private final static String OR_SEPARATOR = "+";

  private final static String ATTRIBUTE_SEPARATOR = ".";

  private final static char ESCAPE_CHAR = '\\';

  public static <T> Specification<T> getSpecification(Class<T> type, final DataTablesInput input) {

    return new Specification<T>() {

      @Override
      public Predicate toPredicate(Root<T> root, CriteriaQuery<?> query,
          CriteriaBuilder criteriaBuilder) {

        Predicate predicate = criteriaBuilder.conjunction();

        // check for each searchable column whether a filter value
        // exists
        for (ColumnParameter column : input.getColumns()) {
          String filterValue = column.getSearch().getValue();
          if (column.getSearchable() && StringUtils.hasText(filterValue)) {
            Expression<String> expression = getExpression(root, column.getData());

            if (filterValue.contains(OR_SEPARATOR)) {
              // the filter contains multiple values, add a 'WHERE
              // .. IN' clause
              // Note: "\\" is added to escape special character
              // '+'
              String[] values = filterValue.split("\\" + OR_SEPARATOR);
              if (values.length > 0 && isBoolean(values[0])) {
                Object[] booleanValues = new Boolean[values.length];
                for (int i = 0; i < values.length; i++) {
                  booleanValues[i] = Boolean.valueOf(values[i]);
                }
                predicate =
                    criteriaBuilder.and(predicate, expression.as(Boolean.class).in(booleanValues));
              } else {
                predicate = criteriaBuilder.and(predicate, expression.in(Arrays.asList(values)));
              }
            } else {
              // the filter contains only one value, add a 'WHERE
              // .. LIKE' clause
              if (isBoolean(filterValue)) {
                predicate = criteriaBuilder.and(predicate, criteriaBuilder
                    .equal(expression.as(Boolean.class), Boolean.valueOf(filterValue)));
              } else {
                predicate = criteriaBuilder.and(predicate,
                    criteriaBuilder.like(criteriaBuilder.lower(expression),
                        getLikeFilterValue(filterValue), ESCAPE_CHAR));
              }
            }
          }
        }

        // check whether a global filter value exists
        String globalFilterValue = input.getSearch().getValue();
        if (StringUtils.hasText(globalFilterValue)) {
          Predicate matchOneColumnPredicate = criteriaBuilder.disjunction();
          // add a 'WHERE .. LIKE' clause on each searchable column
          for (ColumnParameter column : input.getColumns()) {
            if (column.getSearchable()) {
              Expression<String> expression = getExpression(root, column.getData());

              matchOneColumnPredicate = criteriaBuilder.or(matchOneColumnPredicate,
                  criteriaBuilder.like(criteriaBuilder.lower(expression),
                      getLikeFilterValue(globalFilterValue), ESCAPE_CHAR));
            }
          }
          predicate = criteriaBuilder.and(predicate, matchOneColumnPredicate);
        }
        // findAll method does a count query first, and then query for the actual data. Yet in the
        // count query, adding a JOIN FETCH results in the following error 'query specified join
        // fetching, but the owner of the fetched association was not present in the select list'
        // see https://jira.spring.io/browse/DATAJPA-105
        boolean isCountQuery = query.getResultType() == Long.class;
        if (isCountQuery) {
          return predicate;
        }
        // add JOIN FETCH when necessary
        for (ColumnParameter column : input.getColumns()) {
          if (!column.getSearchable() || !column.getData().contains(ATTRIBUTE_SEPARATOR)) {
            continue;
          }
          String[] values = column.getData().split("\\" + ATTRIBUTE_SEPARATOR);
          if (root.getModel().getAttribute(values[0])
              .getPersistentAttributeType() == PersistentAttributeType.EMBEDDED) {
            continue;
          }
          Fetch<?, ?> fetch = null;
          for (int i = 0; i < values.length - 1; i++) {
            fetch = (fetch == null ? root : fetch).fetch(values[i], JoinType.LEFT);
          }
        }
        return predicate;
      }

    };
  }

  public static com.mysema.query.types.Predicate getPredicate(PathBuilder<?> entity,
      DataTablesInput input) {

    BooleanBuilder predicate = new BooleanBuilder();
    // check for each searchable column whether a filter value exists
    for (ColumnParameter column : input.getColumns()) {
      String filterValue = column.getSearch().getValue();
      if (column.getSearchable() && StringUtils.hasText(filterValue)) {

        if (filterValue.contains(OR_SEPARATOR)) {
          // the filter contains multiple values, add a 'WHERE .. IN'
          // clause
          // Note: "\\" is added to escape special character '+'
          String[] values = filterValue.split("\\" + OR_SEPARATOR);
          if (values.length > 0 && isBoolean(values[0])) {
            List<Boolean> booleanValues = new ArrayList<Boolean>();
            for (int i = 0; i < values.length; i++) {
              booleanValues.add(Boolean.valueOf(values[i]));
            }
            predicate = predicate.and(entity.getBoolean(column.getData()).in(booleanValues));
          } else {
            predicate.and(getStringExpression(entity, column.getData()).in(values));
          }
        } else {
          // the filter contains only one value, add a 'WHERE .. LIKE'
          // clause
          if (isBoolean(filterValue)) {
            predicate =
                predicate.and(entity.getBoolean(column.getData()).eq(Boolean.valueOf(filterValue)));
          } else {
            predicate = predicate.and(getStringExpression(entity, column.getData()).lower()
                .like(getLikeFilterValue(filterValue), ESCAPE_CHAR));
          }
        }
      }
    }

    // check whether a global filter value exists
    String globalFilterValue = input.getSearch().getValue();
    if (StringUtils.hasText(globalFilterValue)) {
      BooleanBuilder matchOneColumnPredicate = new BooleanBuilder();
      // add a 'WHERE .. LIKE' clause on each searchable column
      for (ColumnParameter column : input.getColumns()) {
        if (column.getSearchable()) {
          matchOneColumnPredicate =
              matchOneColumnPredicate.or(getStringExpression(entity, column.getData()).lower()
                  .like(getLikeFilterValue(globalFilterValue), ESCAPE_CHAR));
        }
      }
      predicate = predicate.and(matchOneColumnPredicate);
    }
    return predicate;
  }

  /**
   * Creates a 'LIMIT .. OFFSET .. ORDER BY ..' clause for the given {@link DataTablesInput}.
   * 
   * @param input the {@link DataTablesInput} mapped from the Ajax request
   * @return a {@link Pageable}, must not be {@literal null}.
   */
  public static Pageable getPageable(DataTablesInput input) {
    List<Order> orders = new ArrayList<Order>();
    for (OrderParameter order : input.getOrder()) {
      ColumnParameter column = input.getColumns().get(order.getColumn());
      if (column.getOrderable()) {
        String sortColumn = column.getData();
        Direction sortDirection = Direction.fromString(order.getDir());
        orders.add(new Order(sortDirection, sortColumn));
      }
    }
    Sort sort = orders.isEmpty() ? null : new Sort(orders);

    if (input.getLength() == -1) {
      input.setStart(0);
      input.setLength(Integer.MAX_VALUE);
    }
    return new PageRequest(input.getStart() / input.getLength(), input.getLength(), sort);
  }

  private static boolean isBoolean(String filterValue) {
    return "TRUE".equalsIgnoreCase(filterValue) || "FALSE".equalsIgnoreCase(filterValue);
  }

  private static Expression<String> getExpression(Root<?> root, String columnData) {
    if (columnData.contains(ATTRIBUTE_SEPARATOR)) {
      // columnData is like "joinedEntity.attribute" so add a join clause
      String[] values = columnData.split("\\" + ATTRIBUTE_SEPARATOR);
      if (root.getModel().getAttribute(values[0])
          .getPersistentAttributeType() == PersistentAttributeType.EMBEDDED) {
        // with @Embedded attribute
        return root.get(values[0]).get(values[1]).as(String.class);
      }
      From<?, ?> from = root;
      for (int i = 0; i < values.length - 1; i++) {
        from = from.join(values[i], JoinType.LEFT);
      }
      return from.get(values[values.length - 1]).as(String.class);
    } else {
      // columnData is like "attribute" so nothing particular to do
      return root.get(columnData).as(String.class);
    }
  }

  private static String getLikeFilterValue(String filterValue) {
    return "%"
        + filterValue.toLowerCase().replaceAll("%", "\\\\" + "%").replaceAll("_", "\\\\" + "_")
        + "%";
  }

  private static StringExpression getStringExpression(PathBuilder<?> entity, String columnData) {
    return Expressions.stringOperation(Ops.STRING_CAST, entity.get(columnData));
  }

}
