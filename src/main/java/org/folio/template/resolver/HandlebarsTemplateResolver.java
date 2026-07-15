package org.folio.template.resolver;

import static org.folio.HttpStatus.SC_BAD_REQUEST;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
import java.time.temporal.TemporalAccessor;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.template.util.TemplateEngineHelper;

import com.github.jknack.handlebars.Context;
import com.github.jknack.handlebars.EscapingStrategy;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.HandlebarsException;
import com.github.jknack.handlebars.Options;
import com.github.jknack.handlebars.TagType;
import com.github.jknack.handlebars.cache.ConcurrentMapTemplateCache;
import com.github.jknack.handlebars.helper.ConditionalHelpers;
import com.github.jknack.handlebars.helper.StringHelpers;

import io.vertx.core.Future;
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
    // nl2br: HTML-escape the value, then turn CRLF/CR/LF line breaks into <br>. The result is
    // wrapped in a SafeString so the already-escaped markup is emitted verbatim. Authors use it
    // as {{nl2br order.notes}} to preserve multi-line text in HTML email output.
    this.handlebars.registerHelper("nl2br", (Object value, Options options) -> {
      if (value == null) {
        return "";
      }
      String escaped = Handlebars.Utils.escapeExpression(value.toString()).toString();
      String withBreaks = escaped.replaceAll("\\r\\n|\\r|\\n", "<br>\n");
      return new Handlebars.SafeString(withBreaks);
    });
    // numberFormat: locale-aware number formatting, the Java equivalent of Intl.NumberFormat.
    // Used as {{numberFormat amount locale="de-DE" minDecimals=2 maxDecimals=2}}.
    // locale defaults to the tenant locale, then en-US. min/maxDecimals are optional.
    this.handlebars.registerHelper("numberFormat", (Object value, Options options) -> {
      if (value == null) {
        return "";
      }
      double number = value instanceof Number n ? n.doubleValue() : Double.parseDouble(value.toString());
      // locale precedence: explicit hash > tenant locale carried in the context > en-US.
      String localeTag = options.hash("locale");
      if (localeTag == null) {
        localeTag = options.get(TemplateEngineHelper.TENANT_LOCALE_CONTEXT_KEY);
      }
      Locale locale = Locale.forLanguageTag(StringUtils.defaultIfBlank(localeTag, "en-US"));
      NumberFormat formatter = NumberFormat.getNumberInstance(locale);
      Number minDecimals = options.hash("minDecimals");
      if (minDecimals != null) {
        formatter.setMinimumFractionDigits(minDecimals.intValue());
      }
      Number maxDecimals = options.hash("maxDecimals");
      if (maxDecimals != null) {
        formatter.setMaximumFractionDigits(maxDecimals.intValue());
      }
      return formatter.format(number);
    });
    // dateFormat: locale-aware date formatting for raw ISO date values. Note tokens ending in
    // Date/DateTime/DetailedDateTime are already localized by ContextDateTimeFormatter before
    // rendering, so this is meant for other ISO date values. locale precedence matches
    // numberFormat (hash > tenant locale > en-US). An optional pattern overrides the localized
    // style; otherwise a localized MEDIUM date is used. Unparseable input is returned unchanged
    // so a bad value never fails the whole render.
    // Used as {{dateFormat someIsoDate locale="de-DE" pattern="yyyy-MM-dd"}}.
    this.handlebars.registerHelper("dateFormat", (Object value, Options options) -> {
      if (value == null) {
        return "";
      }
      String raw = value.toString();
      if (StringUtils.isBlank(raw)) {
        return "";
      }
      String localeTag = options.hash("locale");
      if (localeTag == null) {
        localeTag = options.get(TemplateEngineHelper.TENANT_LOCALE_CONTEXT_KEY);
      }
      Locale locale = Locale.forLanguageTag(StringUtils.defaultIfBlank(localeTag, "en-US"));
      TemporalAccessor temporal = parseIsoDateTime(raw);
      if (temporal == null) {
        LOG.debug("dateFormat:: value is not an ISO date, returning unchanged: {}", raw);
        return raw;
      }
      String pattern = options.hash("pattern");
      DateTimeFormatter formatter = pattern != null
        ? DateTimeFormatter.ofPattern(pattern, locale)
        : DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale);
      try {
        return formatter.format(temporal);
      } catch (java.time.DateTimeException e) {
        // e.g. a time-based pattern applied to a date-only value
        LOG.debug("dateFormat:: cannot format {} with pattern {}: {}", raw, pattern, e.getMessage());
        return raw;
      }
    });
    // equalsAny: string-equality membership test. Matches when the first value's string form equals
    // any of the remaining arguments' string forms. A null/absent value yields no match; every
    // argument is coerced to its string form (so numbers etc. compare as text). Works both as a
    // block helper -- {{#equalsAny orderLine.orderFormat "P/E Mix" "Physical Resource"}}...{{else}}
    // ...{{/equalsAny}} renders the fn/inverse branch -- and inline/subexpression, where it returns
    // the boolean, e.g. {{#if (equalsAny orderLine.orderFormat "P/E Mix" "Physical Resource")}}...{{/if}}.
    this.handlebars.registerHelper("equalsAny", (Object value, Options options) -> {
      boolean matched = false;
      if (value != null) {
        String target = value.toString();
        for (Object candidate : options.params) {
          if (candidate != null && target.equals(candidate.toString())) {
            matched = true;
            break;
          }
        }
      }
      if (options.tagType == TagType.SECTION) {
        return matched ? options.fn() : options.inverse();
      }
      return matched;
    });
  }

  // Parses an ISO-8601 date or date-time, tolerating an optional zone offset and date-only
  // values. Returns null when the input is not a recognizable ISO temporal.
  private static TemporalAccessor parseIsoDateTime(String raw) {
    try {
      return OffsetDateTime.parse(raw);
    } catch (DateTimeParseException ignored) {
      // not an offset date-time, fall through
    }
    try {
      return LocalDateTime.parse(raw);
    } catch (DateTimeParseException ignored) {
      // not a local date-time, fall through
    }
    try {
      return LocalDate.parse(raw);
    } catch (DateTimeParseException ignored) {
      return null;
    }
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
}
