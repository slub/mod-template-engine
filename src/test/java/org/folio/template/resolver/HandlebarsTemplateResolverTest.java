package org.folio.template.resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.serviceproxy.ServiceException;

class HandlebarsTemplateResolverTest {

  private final HandlebarsTemplateResolver resolver = new HandlebarsTemplateResolver();

  private String render(String body, JsonObject context) {
    Future<JsonObject> future = resolver.processTemplate(
      new JsonObject().put("body", body), context, "text/html");
    assertTrue(future.succeeded(),
      () -> "render failed: " + (future.cause() == null ? "" : future.cause().getMessage()));
    return future.result().getString("body");
  }

  @Test
  void plainTokenSubstitution() {
    JsonObject context = new JsonObject().put("user", new JsonObject().put("name", "Ada"));
    assertEquals("Hello Ada", render("Hello {{user.name}}", context));
  }

  @Test
  void missingTokenRendersEmptyString() {
    assertEquals("Hello ", render("Hello {{user.name}}", new JsonObject()));
  }

  @Test
  void conditionalAndEachAndComparisonHelpers() {
    JsonObject context = new JsonObject()
      .put("count", 7)
      .put("items", new JsonArray().add("a").add("b").add("c"));
    assertEquals("big", render("{{#if (gt count 5)}}big{{else}}small{{/if}}", context));
    assertEquals("abc", render("{{#each items}}{{this}}{{/each}}", context));
  }

  @Test
  void lookupSelectsArrayElementByIndex() {
    JsonObject context = new JsonObject()
      .put("user", new JsonObject().put("phrases", new JsonArray().add("first").add("second")));
    assertEquals("second", render("{{lookup user.phrases 1}}", context));
  }

  @Test
  void containsAndContainsAllOverRealContextType() {
    // Built as JsonObject then read via mapTo(Context.class) - exactly the proxy path - so the
    // array surfaces as an ArrayList (a Collection), which is the type the helper must handle.
    JsonObject context = new JsonObject().put("user",
      new JsonObject().put("phrases", new JsonArray().add("NOW!").add("WTF").add("x")));

    assertEquals("YES",
      render("{{#if (containsAll user.phrases \"NOW!\" \"WTF\")}}YES{{else}}NO{{/if}}", context));
    assertEquals("YES",
      render("{{#if (contains user.phrases \"NOW!\")}}YES{{else}}NO{{/if}}", context));
    assertEquals("NO",
      render("{{#if (containsAll user.phrases \"NOW!\" \"MISSING\")}}YES{{else}}NO{{/if}}", context));
  }

  @Test
  void membershipHelpersOnEmptyMissingOrScalarReturnFalse() {
    String template = "{{#if (contains user.phrases \"NOW!\")}}YES{{else}}NO{{/if}}";
    // empty list
    assertEquals("NO", render(template,
      new JsonObject().put("user", new JsonObject().put("phrases", new JsonArray()))));
    // missing field entirely
    assertEquals("NO", render(template, new JsonObject()));
    // scalar (not an array) - membership is array-only, so no match even though the value equals
    assertEquals("NO", render(template,
      new JsonObject().put("user", new JsonObject().put("phrases", "NOW!"))));
  }

  @Test
  void asListHandlesEveryRuntimeShape() {
    assertEquals(List.of("a", "b"), HandlebarsTemplateResolver.asList(List.of("a", "b")));
    assertEquals(List.of("a", "b"), HandlebarsTemplateResolver.asList(new JsonArray().add("a").add("b")));
    assertEquals(List.of("a", "b"), HandlebarsTemplateResolver.asList(new Object[] {"a", "b"}));
    assertTrue(HandlebarsTemplateResolver.asList(null).isEmpty());
    assertTrue(HandlebarsTemplateResolver.asList("scalar").isEmpty());
    assertTrue(HandlebarsTemplateResolver.asList(42).isEmpty());
  }

  @Test
  void htmlEscapingSetIsPinned() {
    // jknack default HTML_ENTITY escaping is a superset of Mustache: & < > " ' PLUS backtick and =.
    JsonObject context = new JsonObject().put("val", "<>&\"'`=");
    assertEquals("&lt;&gt;&amp;&quot;&#x27;&#x60;&#x3D;", render("{{val}}", context));
    // triple-mustache emits raw (barcode tokens rely on this)
    assertEquals("<>&\"'`=", render("{{{val}}}", context));
  }

  @Test
  void malformedTemplateFailsWithClientError() {
    Future<JsonObject> future = resolver.processTemplate(
      new JsonObject().put("body", "{{#if x}}never closed"), new JsonObject(), "text/html");
    assertTrue(future.failed());
    assertInstanceOf(ServiceException.class, future.cause());
    assertEquals(400, ((ServiceException) future.cause()).failureCode());
  }
}
