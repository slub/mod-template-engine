package org.folio.template.resolver;

import static org.folio.HttpStatus.SC_BAD_REQUEST;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.github.jknack.handlebars.Context;
import com.github.jknack.handlebars.EscapingStrategy;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.HandlebarsException;
import com.github.jknack.handlebars.Helper;
import com.github.jknack.handlebars.cache.ConcurrentMapTemplateCache;
import com.github.jknack.handlebars.helper.ConditionalHelpers;
import com.github.jknack.handlebars.helper.StringHelpers;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.serviceproxy.ServiceException;

public class HandlebarsTemplateResolver implements TemplateResolver {

  private static final Logger LOG = LogManager.getLogger("mod-template-engine");

  private final Handlebars handlebars;

  public HandlebarsTemplateResolver() {
    // Single reusable instance. ConcurrentMapTemplateCache avoids recompiling stored
    // templates on every render (H4); the default NullTemplateCache would recompile on
    // the event-loop thread each time. Cache size is bounded by the set of stored templates.
    // Keep the default HTML escaping (a superset of Mustache - escapes & < > " ' plus
    // backtick and =); set it explicitly for clarity (H5). Missing tokens render as empty
    // string (lenient mode, matching Mustache).
    this.handlebars = new Handlebars()
      .with(EscapingStrategy.HTML_ENTITY)
      .with(new ConcurrentMapTemplateCache());
    this.handlebars.registerHelpers(ConditionalHelpers.class);
    this.handlebars.registerHelpers(StringHelpers.class);
    this.handlebars.registerHelper("contains", containsHelper());
    this.handlebars.registerHelper("containsAll", containsAllHelper());
  }

  @Override
  public Future<JsonObject> processTemplate(JsonObject templateContent, JsonObject context, String outputFormat) {
    LOG.debug("processTemplate:: Processing Template");
    JsonObject result = new JsonObject();
    try {
      Map<String, Object> contextMap = Optional.of(context)
        .map(jsonObject -> jsonObject.mapTo(org.folio.rest.jaxrs.model.Context.class))
        .map(org.folio.rest.jaxrs.model.Context::getAdditionalProperties)
        .orElse(null);
      for (Map.Entry<String, Object> property : templateContent) {
        if (property.getValue() instanceof String) {
          result.put(property.getKey(), processTemplateProperty(property.getValue().toString(), contextMap));
        }
      }
      return Future.succeededFuture(result);
    } catch (HandlebarsException e) {
      // Malformed author syntax (unclosed block, unknown helper) is a client error, not a
      // server fault. The original type is erased across the EventBus service proxy, so we
      // signal it with a 400 failure code that mapExceptionToResponse translates to HTTP 400 (H2).
      LOG.warn("Malformed Handlebars template: {}", e.getMessage());
      return Future.failedFuture(new ServiceException(SC_BAD_REQUEST,
        "Failed to process template: " + e.getMessage()));
    } catch (Exception e) {
      LOG.warn("Failed to Process Template {}", e.getMessage());
      return Future.failedFuture(e);
    }
  }

  private String processTemplateProperty(String templateProperty, Map<String, Object> contextMap) throws java.io.IOException {
    LOG.debug("processTemplateProperty:: Processing template property");
    Context context = Context.newContext(contextMap);
    String processed = handlebars.compileInline(templateProperty).apply(context);
    LOG.info("processTemplateProperty:: Processed template property");
    return processed;
  }

  /**
   * {@code (contains list value)} - true when {@code value} is a member of the array {@code list}.
   * Membership is string-equality (template literals arrive as String).
   */
  private Helper<Object> containsHelper() {
    return (listValue, options) -> contains(asList(listValue), options.param(0, null));
  }

  /**
   * {@code (containsAll list v1 v2 ...)} - true when every argument is a member of {@code list}.
   */
  private Helper<Object> containsAllHelper() {
    return (listValue, options) -> {
      List<Object> list = asList(listValue);
      for (Object target : options.params) {
        if (!contains(list, target)) {
          return Boolean.FALSE;
        }
      }
      return Boolean.TRUE;
    };
  }

  private static boolean contains(List<Object> list, Object target) {
    String targetString = stringify(target);
    return list.stream().anyMatch(element -> Objects.equals(stringify(element), targetString));
  }

  /**
   * Normalises whatever runtime shape an array value arrives as into a {@code List}.
   * The context crosses the EventBus service proxy and is read via mapTo(Context.class), so a
   * JSON array is typically a {@link Collection} (ArrayList), but other paths can surface a
   * Vert.x {@link JsonArray} (which is NOT a Collection) or an {@code Object[]}. Anything else
   * (null, scalar, object/map) yields an empty list, so membership simply returns false (H3).
   */
  static List<Object> asList(Object value) {
    if (value instanceof Collection<?> collection) {
      return new ArrayList<>(collection);
    }
    if (value instanceof JsonArray jsonArray) {
      return jsonArray.getList();
    }
    if (value instanceof Object[] array) {
      return Arrays.asList(array);
    }
    return Collections.emptyList();
  }

  private static String stringify(Object value) {
    return value == null ? null : value.toString();
  }
}
