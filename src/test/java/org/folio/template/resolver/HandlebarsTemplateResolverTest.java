package org.folio.template.resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
  void htmlEscapingSetIsPinned() {
    // jknack default HTML_ENTITY escaping is a superset of Mustache: & < > " ' PLUS backtick and =.
    JsonObject context = new JsonObject().put("val", "<>&\"'`=");
    assertEquals("&lt;&gt;&amp;&quot;&#x27;&#x60;&#x3D;", render("{{val}}", context));
    // triple-mustache emits raw (barcode tokens rely on this)
    assertEquals("<>&\"'`=", render("{{{val}}}", context));
  }

  @Test
  void equalsAnyMatchesAnyCandidate() {
    JsonObject context = new JsonObject().put("format", "P/E Mix");
    assertEquals("yes", render("{{#if (equalsAny format \"Physical Resource\" \"P/E Mix\")}}yes{{else}}no{{/if}}", context));
    // whole-string equality, not substring
    assertEquals("no", render("{{#if (equalsAny format \"Mix\")}}yes{{else}}no{{/if}}", context));
    // missing/null value yields false rather than failing
    assertEquals("no", render("{{#if (equalsAny missing \"x\")}}yes{{else}}no{{/if}}", new JsonObject()));
  }

  @Test
  void equalsAnyWorksAsBlockHelper() {
    JsonObject context = new JsonObject().put("format", "P/E Mix");
    assertEquals("yes", render("{{#equalsAny format \"Physical Resource\" \"P/E Mix\"}}yes{{else}}no{{/equalsAny}}", context));
    assertEquals("no", render("{{#equalsAny format \"Physical Resource\"}}yes{{else}}no{{/equalsAny}}", context));
  }

  private JsonObject contributorsContext() {
    return new JsonObject()
      .put("label", "Authors")
      .put("contributors", new JsonArray()
        .add(contributor("Ada", "Personal name"))
        .add(contributor("ACME Corp", "Corporate name"))
        .add(contributor("Grace", "Personal name")));
  }

  private JsonObject contributor(String name, String type) {
    return new JsonObject()
      .put("contributor", name)
      .put("contributorNameType", new JsonObject().put("name", type));
  }

  @Test
  void whereRendersOnlyElementsMatchingNestedPath() {
    assertEquals("Ada; Grace", render(
      "{{#where contributors \"contributorNameType.name\" \"Personal name\"}}"
        + "{{#unless @first}}; {{/unless}}{{contributor}}{{/where}}",
      contributorsContext()));
  }

  @Test
  void whereLoopVariablesCountFilteredMatchesOnly() {
    // @index/@index_1/@last must refer to the filtered list, not the source list, where
    // Grace is the third element.
    assertEquals("0/1:Ada,1/2:Grace.", render(
      "{{#where contributors \"contributorNameType.name\" \"Personal name\"}}"
        + "{{@index}}/{{@index_1}}:{{contributor}}{{#if @last}}.{{else}},{{/if}}{{/where}}",
      contributorsContext()));
  }

  @Test
  void whereSupportsBlockParamsAndParentContext() {
    assertEquals("Authors 0=Ada Authors 1=Grace ", render(
      "{{#where contributors \"contributorNameType.name\" \"Personal name\" as |person idx|}}"
        + "{{../label}} {{idx}}={{person.contributor}} {{/where}}",
      contributorsContext()));
  }

  @Test
  void whereRendersElseBlockWhenNothingMatches() {
    assertEquals("none", render(
      "{{#where contributors \"contributorNameType.name\" \"Meeting name\"}}{{contributor}}{{else}}none{{/where}}",
      contributorsContext()));
  }

  @Test
  void whereRendersElseBlockWhenValueIsNotAList() {
    JsonObject context = new JsonObject().put("contributors", "Ada");
    assertEquals("none", render(
      "{{#where contributors \"contributorNameType.name\" \"Personal name\"}}x{{else}}none{{/where}}", context));
    assertEquals("none", render(
      "{{#where missing \"contributorNameType.name\" \"Personal name\"}}x{{else}}none{{/where}}", new JsonObject()));
  }

  @Test
  void whereComparesByStringForm() {
    JsonObject context = new JsonObject().put("lines", new JsonArray()
      .add(new JsonObject().put("qty", 2).put("title", "A"))
      .add(new JsonObject().put("qty", 3).put("title", "B")));
    assertEquals("A", render("{{#where lines \"qty\" \"2\"}}{{title}}{{/where}}", context));
  }

  @Test
  void whereWithNullExpectedMatchesElementsWithoutValue() {
    // An absent variable as the expected value resolves to null and matches only absent/null paths.
    JsonObject context = new JsonObject().put("lines", new JsonArray()
      .add(new JsonObject().put("code", "X").put("title", "A"))
      .add(new JsonObject().put("title", "B")));
    assertEquals("B", render("{{#where lines \"code\" undefinedToken}}{{title}}{{/where}}", context));
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
