/*
 * Copyright (C) 2018 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.escapevelocity;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.common.truth.TruthJUnit.assume;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.truth.Expect;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.exception.VelocityException;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.StringResourceLoader;
import org.apache.velocity.runtime.resource.util.StringResourceRepository;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * @author emcmanus@google.com (Éamonn McManus)
 */
@RunWith(JUnit4.class)
public class TemplateTest {
  @Rule public TestName testName = new TestName();
  @Rule public Expect expect = Expect.create();

  private enum Version {V1, V2}
  private static final Version VERSION;

  static {
    Version version;
    try {
      // The Runtime class was deprecated in v1.7 and deleted in v2.0.
      Class.forName("org.apache.velocity.runtime.Runtime");
      version = Version.V1;
    } catch (ClassNotFoundException e) {
      version = Version.V2;
    }
    VERSION = version;
  }

  private VelocityEngine newVelocityEngine() {
    VelocityEngine engine = new VelocityEngine();

    // Ensure that $undefinedvar will produce an exception rather than outputting $undefinedvar.
    engine.setProperty(RuntimeConstants.RUNTIME_REFERENCES_STRICT, "true");

    // Set properties to make Velocity 2.x more like 1.7.
    engine.setProperty("directive.if.empty_check", "false");
    engine.setProperty("parser.allow_hyphen_in_identifiers", "true");
    engine.setProperty("parser.space_gobbling", "bc");

    // Disable any logging that Velocity might otherwise see fit to do.
    // This has no effect on V2, but there you can shut logging up via slf4j.
    engine.setProperty(
        "runtime.log.logsystem.class", "org.apache.velocity.runtime.log.NullLogChute");

    return engine;
  }

  private void compare(String template) {
    compare(template, ImmutableMap.<String, Object>of());
  }

  private void compare(String template, Map<String, ?> vars) {
    compare(template, () -> vars);
  }

  /**
   * Checks that the given template and the given variables produce identical results with
   * Velocity and EscapeVelocity. This uses a {@code Supplier} to define the variables to cover
   * test cases that involve modifying the values of the variables. Otherwise the run using
   * Velocity would change those values so that the run using EscapeVelocity would not be starting
   * from the same point.
   */
  private void compare(String template, Supplier<? extends Map<String, ?>> varsSupplier) {
    Map<String, ?> velocityVars = varsSupplier.get();
    String velocityRendered = velocityRender(template, velocityVars);
    Map<String, ?> escapeVelocityVars = varsSupplier.get();
    String escapeVelocityRendered;
    try {
      escapeVelocityRendered =
          Template.parseFrom(new StringReader(template)).evaluate(escapeVelocityVars);
    } catch (Exception e) {
      throw new AssertionError(
          "EscapeVelocity failed, but Velocity succeeded and returned: <" + velocityRendered + ">",
          e);
    }
    String failure = "from Velocity: <" + velocityRendered + ">\n"
        + "from EscapeVelocity: <" + escapeVelocityRendered + ">\n";
    expect.withMessage(failure).that(escapeVelocityRendered).isEqualTo(velocityRendered);
  }

  private String velocityRender(String template, Map<String, ?> vars) {
    VelocityEngine velocityEngine = newVelocityEngine();
    velocityEngine.init();
    VelocityContext velocityContext = new VelocityContext(new TreeMap<>(vars));
    StringWriter writer = new StringWriter();
    String templateName = testName.getMethodName();
    boolean rendered =
        velocityEngine.evaluate(velocityContext, writer, templateName, new StringReader(template));
    assertWithMessage(templateName).that(rendered).isTrue();
    return writer.toString();
  }

  private void expectException(
      String template,
      String expectedMessageSubstring) {
    expectException(template, ImmutableMap.of(), expectedMessageSubstring);
  }

  private void expectException(
      String template,
      Map<String, ?> vars,
      String expectedMessageSubstring) {
    VelocityEngine velocityEngine = newVelocityEngine();
    velocityEngine.init();
    VelocityContext velocityContext = new VelocityContext(new TreeMap<>(vars));
    String templateName = testName.getMethodName();
    VelocityException velocityException =
        assertThrows(
            "Velocity did not throw an exception for this template",
            VelocityException.class,
            () ->
                velocityEngine.evaluate(
                    velocityContext, new StringWriter(), templateName, new StringReader(template)));
    try {
      Template parsedTemplate = Template.parseFrom(new StringReader(template));
      parsedTemplate.evaluate(vars);
      fail("Velocity generated an exception, but EscapeVelocity did not: " + velocityException);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (ParseException | EvaluationException expected) {
      assertWithMessage("Got expected exception, but message did not match")
          .that(expected).hasMessageThat().contains(expectedMessageSubstring);
    }
  }

  @Test
  public void empty() {
    compare("");
  }

  @Test
  public void literalOnly() {
    compare("In the reign of James the Second \n It was generally reckoned\n");
  }

  @Test
  public void lineComment() {
    compare("line 1 ##\n  line 2");
  }

  @Test
  public void blockComment() {
    compare("line 1 #* blah\n line 2 * #\n line 3 *#  \n line 4");
    compare("foo #*# bar *# baz");
    compare("foo #* one *# #* two *# #* three *#");
    compare("foo #** bar *# #* baz **#");
  }

  @Test
  public void ignoreHashIfNotDirectiveOrComment() {
    compare("# if is not a directive because of the space");
    compare("#<foo>");
    compare("# <foo>");
    compare("${foo}#${bar}", ImmutableMap.of("foo", "xxx", "bar", "yyy"));
  }

  @Test
  public void ignoreUnrecognizedDirective() {
    compare("<a href=\"http://google.com/foo#bar\">");
    compare("#bar");
    // Using a name like #endfoo sometimes triggers an exception with Velocity. Here we check
    // whether any other standard directive string has this behaviour, and apparently none do.
    compare("#breakx");
    compare("#definex");
    compare("#elseifx");
    compare("#elsex");
    compare("#evaluatex");
    compare("#foreachx");
    compare("#ifx");
    compare("#includex");
    compare("#macrox");
    compare("#parsex");
    compare("#setx");
    compare("#stopx");
    expectException("#endx", "Unrecognized directive #endx");
  }

  // Since we are lax with #foo in general, make sure we don't just ignore Velocity directives that
  // we don't support. There are two varieties: ones that must be followed by `(`, which we will
  // treat as undefined macros; and ones that are not necessarily followed by `(`, which we handle
  // explicitly.

  @Test
  public void unsupportedDirectives_paren() throws Exception {
    String[] unsupportedDirectives = {
      "#include('x.vm')",
    };
    for (String unsupportedDirective : unsupportedDirectives) {
      Template template = Template.parseFrom(new StringReader(unsupportedDirective));
      EvaluationException e =
          assertThrows(
              unsupportedDirective,
              EvaluationException.class,
              () -> template.evaluate(ImmutableMap.of()));
      assertThat(e)
          .hasMessageThat()
          .contains("is neither a standard directive nor a macro");
    }
  }

  @Test
  public void unsupportedDirectives_noParen() {
    String[] unsupportedDirectives = {"#stop"};
    for (String unsupportedDirective : unsupportedDirectives) {
      ParseException e =
          assertThrows(
              unsupportedDirective,
              ParseException.class,
              () -> Template.parseFrom(new StringReader(unsupportedDirective)));
      assertThat(e)
          .hasMessageThat()
          .contains("EscapeVelocity does not currently support " + unsupportedDirective);
    }
  }

  @Test
  public void blockQuote() {
    compare("#[[]]#");
    compare("x#[[]]#y");
    compare("#[[$notAReference #notADirective]]#");
    compare("#[[ [[  ]]  ]#  ]]#");
    compare("#[ foo");
    compare("x\n  #[[foo\nbar\nbaz]]#y");
  }

  @Test
  public void substituteNoBraces() {
    compare(" $x ", ImmutableMap.of("x", 1729));
    compare(" ! $x ! ", ImmutableMap.of("x", 1729));
  }

  @Test
  public void dollarWithoutId() {
    compare(" $? ");
    compare(" $$ ");
    compare(" $. ");
    compare(" $[ ");
  }

  @Test
  public void doubleDollar() {
    // The first $ is plain text and the second one starts a reference.
    compare(" $$foo ", ImmutableMap.of("foo", true));
    compare(" $${foo} ", ImmutableMap.of("foo", true));
    compare(" $!$foo ", ImmutableMap.of("foo", true));
  }

  @Test
  public void substituteWithBraces() {
    compare("a${x}\nb", ImmutableMap.of("x", "1729"));
  }

  @Test
  public void substitutePropertyNoBraces() {
    compare("=$t.name=", ImmutableMap.of("t", Thread.currentThread()));
  }

  @Test
  public void substitutePropertyWithBraces() {
    compare("=${t.name}=", ImmutableMap.of("t", Thread.currentThread()));
  }

  @Test
  public void braceNotFollowedById() {
    compare("${??");
    compare("$!{??");
  }

  @Test
  public void substituteNotPropertyId() {
    compare("$foo.!", ImmutableMap.of("foo", false));
  }

  @Test
  public void substituteNestedProperty() {
    compare("\n$t.name.empty\n", ImmutableMap.of("t", Thread.currentThread()));
  }

  @Test
  public void substituteUndefinedReference() {
    expectException("$foo", ImmutableMap.of(), "Undefined reference $foo");
  }

  @Test
  public void substituteMethodNoArgs() {
    compare("<$c.size()>", ImmutableMap.of("c", ImmutableMap.of()));
  }

  @Test
  public void substituteMethodNoArgsSyntheticOverride() {
    compare("<$c.isEmpty()>", ImmutableMap.of("c", ImmutableSetMultimap.of()));
  }

  @Test
  public void substituteMethodOneArg() {
    compare("<$list.get(0)>", ImmutableMap.of("list", ImmutableList.of("foo")));
  }

  @Test
  public void substituteMethodOneNullArg() {
    compare("<$map.containsKey( null )>", ImmutableMap.of("map", ImmutableMap.of()));
  }

  @Test
  public void substituteMethodTwoArgs() {
    compare("\n$s.indexOf(\"bar\", 2)\n", ImmutableMap.of("s", "barbarbar"));
  }

  @Test
  public void substituteMethodExpressionArg() {
    expectException("$sb.append(2 + 3) $sb", "Expected )");
  }

  @Test
  public void substituteMethodSyntheticOverloads() {
    // If we aren't careful, we'll see both the inherited `Set<K> keySet()` from Map
    // and the overridden `ImmutableSet<K> keySet()` in ImmutableMap.
    compare("$map.keySet()", ImmutableMap.of("map", ImmutableMap.of("foo", "bar")));
  }

  @Test
  public void substituteStaticMethod() {
    compare("$Integer.toHexString(23)", ImmutableMap.of("Integer", Integer.class));
  }

  @Test
  public void substituteMethodNullLiteral() {
    // Velocity recognizes the null literal, but only in this exact spot, as a method parameter.
    // You can't say `#set($foo = null)` for example. Why not? Because.
    compare(
        "<$Objects.isNull(null) <$Objects.equals(null, null)>",
        ImmutableMap.of("Objects", Objects.class));
  }

  @Test
  public void substituteStaticMethodAsInstanceMethod() {
    compare("$i.toHexString(23)", ImmutableMap.of("i", 0));
  }

  @Test
  public void substituteClassMethod() {
    // This is Class.getName().
    compare("$Integer.getName()", ImmutableMap.of("Integer", Integer.class));
  }

  /** See {@link #substituteClassOrInstanceMethod}. */
  public static class GetName {
    public static String getName() {
      return "Noddy";
    }
  }

  @Test
  public void substituteClassOrInstanceMethod() {
    // If the method exists as both an instance method on Class and a static method on the named
    // class, it's the instance method that wins, so this is still Class.getName().
    compare("$GetName.getName()", ImmutableMap.of("GetName", GetName.class));
  }

  @Test
  public void substituteMethodOnNull() {
    expectException(
        "$foo.bar()",
        Collections.singletonMap("foo", null),
        "In $foo.bar(): $foo must not be null");
  }

  @Test
  public void substituteMethodNonExistent() {
    expectException(
        "$i.nonExistent($i)",
        ImmutableMap.of("i", 23),
        "In $i.nonExistent($i): no method nonExistent in java.lang.Integer");
  }

  @Test
  public void substituteMethodWrongArguments() {
    expectException(
        "$s.charAt()",
        ImmutableMap.of("s", ""),
        "In $s.charAt(): parameters for method charAt have wrong types: []");
    expectException(
        "$s.charAt('x')",
        ImmutableMap.of("s", ""),
        "In $s.charAt('x'): parameters for method charAt have wrong types: [x]");
  }

  @Test
  public void substituteMethodAmbiguous() {
    // Below, the null argument matches the (PrintStream) and the (PrintWriter) overloads.
    // We don't test the method strings in the error because their exact format is unspecified.
    expectException(
        "$t.printStackTrace(null)",
        ImmutableMap.of("t", new Throwable()),
        "In $t.printStackTrace(null): ambiguous method invocation, could be one of:");
  }

  @Test
  public void substituteIndexNoBraces() {
    compare("<$map[\"x\"]>", ImmutableMap.of("map", ImmutableMap.of("x", "y")));
    compare("<$map[ \"x\" ]>", ImmutableMap.of("map", ImmutableMap.of("x", "y")));
  }

  @Test
  public void substituteIndexWithBraces() {
    compare("<${map[\"x\"]}>", ImmutableMap.of("map", ImmutableMap.of("x", "y")));
  }

  @Test
  public void substituteIndexNull() {
    // Velocity allows null literals in method parameters but not indexes.
    expectException(
        "<$map[null]>",
        ImmutableMap.of("map", ImmutableMap.of()),
        "Identifier must be preceded by $");
  }

  @Test
  public void substituteIndexExpression() {
    // For no good reason, Velocity doesn't allow arbitrary expressions in indexes, so
    // EscapeVelocity doesn't either.
    expectException("<$map[2 + 3]>", "Expected ]");
  }

  // Velocity allows you to write $map.foo instead of $map["foo"].
  @Test
  public void substituteMapProperty() {
    compare("$map.foo", ImmutableMap.of("map", ImmutableMap.of("foo", "bar")));
    // $map.empty is always equivalent to $map["empty"], never Map.isEmpty().
    compare("$map.empty", ImmutableMap.of("map", ImmutableMap.of("empty", "foo")));
  }

  @Test
  public void substituteIndexThenProperty() {
    compare("<$map[2].name>", ImmutableMap.of("map", ImmutableMap.of(2, getClass())));
  }

  @Test
  public void substituteNegativeIndex() {
    // Negative index means n from the end, e.g. -1 is the last element of the list.
    compare(
        "$list[-1] $list[-2] $list[-3]",
        ImmutableMap.of("list", ImmutableList.of("foo", "bar", "baz")));
  }

  @Test
  public void substituteIndexOnNull() {
    expectException(
        "$foo[23]", Collections.singletonMap("foo", null), "In $foo[23]: $foo must not be null");
  }

  @Test
  public void substituteListIndexNotInteger() {
    expectException(
        "$list[$list[0]]",
        ImmutableMap.of("list", Collections.singletonList(null)),
        "In $list[$list[0]]: list index is not an Integer: null");

    // In V2, a string gets parsed into an integer here, resulting in NumberFormatException.
    // We expect a VelocityException subclass in this test, so for now we skip this check.
    assume().that(VERSION).isEqualTo(Version.V1);
    expectException(
        "$list['x']",
        ImmutableMap.of("list", ImmutableList.of()),
        "In $list['x']: list index is not an Integer: x");
  }

  @Test
  public void substituteListIndexOutOfRange() {
    expectException(
        "$list[17]",
        ImmutableMap.of("list", ImmutableList.of("foo")),
        "In $list[17]: list index 17 is not valid for list of size 1");
    expectException(
        "$list[-2]",
        ImmutableMap.of("list", ImmutableList.of("foo")),
        "In $list[-2]: negative list index -2 counts from the end of the list, but the list size is"
            + " only 1");
  }

  /**
   * A class with a method that returns null. That means that {@code $x.null} and
   * {@code $x.getNull()} both return null if {@code $x} is an instance of this class. If that null
   * ends up being rendered in the output, it should be an error.
   */
  public static class NullHolder {
    public Object getNull() {
      return null;
    }
  }

  /**
   * Tests that it is an error if a null value gets rendered into the output. This is consistent
   * with Velocity.
   */
  @Test
  public void cantRenderNull() {
    expectException("$x", Collections.singletonMap("x", null), "Null value for $x");
    expectException("$x.null", ImmutableMap.of("x", new NullHolder()), "Null value for $x.null");
    expectException("$x.null", ImmutableMap.of("x", ImmutableMap.of()), "Null value for $x.null");
    expectException(
        "$x.getNull()", ImmutableMap.of("x", new NullHolder()), "Null value for $x.getNull()");
    expectException(
        "$x['null']", ImmutableMap.of("x", ImmutableMap.of()), "Null value for $x['null']");
    expectException(
        "$x[\"null\"]", ImmutableMap.of("x", ImmutableMap.of()), "Null value for $x[\"null\"]");
  }

  @Test
  public void canEvaluateNull() {
    compare("#if ($foo == $foo) yes #end", Collections.singletonMap("foo", null));
  }

  @Test
  public void variableNameCantStartWithNonAscii() {
    compare("<$Éamonn>", ImmutableMap.<String, Object>of());
  }

  @Test
  public void variableNamesAreAscii() {
    compare("<$Pádraig>", ImmutableMap.of("P", "(P)"));
  }

  @Test
  public void variableNameCharacters() {
    compare("<${AZaz-foo_bar23}>", ImmutableMap.of("AZaz-foo_bar23", "(P)"));
  }

  /**
   * A public class with a public {@code get} method that has one argument. That means instances can
   * be used like {@code $indexable["foo"]}.
   */
  public static class Indexable {
    public String get(String y) {
      return "[" + y + "]";
    }
  }

  @Test
  public void substituteExoticIndex() {
    // Any class with a get(X) method can be used with $x[i]
    compare("<$x[\"foo\"]>", ImmutableMap.of("x", new Indexable()));
  }

  @Test
  public void substituteInString() {
    String template =
        "#foreach ($a in $list)"
            + "#set ($s = \"THING_${foreach.index}\")"
            + "$s,$s;"
            + "#end";
    compare(template, ImmutableMap.of("list", ImmutableList.of(1, 2, 3)));
    compare("#set ($s = \"$x\") <$s>", ImmutableMap.of("x", "fred"));
    compare("#set ($s = \"==$x$y\") <$s>", ImmutableMap.of("x", "fred", "y", "jim"));
    compare("#set ($s = \"$x$y==\") <$s>", ImmutableMap.of("x", "fred", "y", "jim"));
    compare("#set ($s = \"abc#if (true) yes #else no #{end}def\") $s");
    compare("#set ($s = \"abc\ndef\nghi\") <$s>");
    compare("#set ($s = \"<#double(17)>\") #macro(double $n) #set ($x = 2 * $n) $x #end $s");
    Function<String, String> quote = s -> "«" + s + "»";
    compare("<$quote.apply(\"#foreach ($a in $list)$a#end\")>",
        ImmutableMap.of("quote", quote, "list", ImmutableList.of("foo", "bar", "baz")));
  }

  @Test
  public void stringOperationsOnSubstitution() {
    compare("#set ($s = \"a${b}c\") $s.length()", ImmutableMap.of("b", 23));
  }

  @Test
  public void singleQuoteNoSubstitution() {
    compare("#set ($s = 'a${b}c') x${s}y", ImmutableMap.of("b", 23));
  }

  @Test
  public void simpleSet() {
    compare("$x#set ($x = 17)#set ($y = 23) ($x, $y)", ImmutableMap.of("x", 1));
  }

  @Test
  public void newlineAfterSet() {
    compare("foo #set ($x = 17)\nbar", ImmutableMap.<String, Object>of());
  }

  @Test
  public void newlineInSet() {
    compare("foo #set ($x\n  = 17)\nbar $x", ImmutableMap.<String, Object>of());
  }

  /**
   * Tests the {@code #define} directive. This is mostly useless since macros do essentially the
   * same thing and have parameters. We support it mainly because it is not much harder than
   * documenting that we don't.
   */
  @Test
  public void define() {
    compare(
        "#define ($hello) Hello, world #set ($x = $x + 1) $x #end $hello $hello",
        ImmutableMap.of("x", 17));
    compare(
        "#define ($hello)\n Hello, world \n#set ($x = $x + 1) $x #end $hello $hello",
        ImmutableMap.of("x", 17));
    compare("#define ($hello) 23 #end #set ($hello = 17) $hello");
    compare("#set ($hello = 17) #define ($hello) 23 skidoo #end $hello");
    compare(
        "Hello ${foo}!\n"
        + "#define ($foo) darkness, my old friend #end\n"
        + "Hello ${foo}!\n",
        ImmutableMap.of("foo", "World"));
    compare(
        "#define ($recur) $x #set ($x = $x - 1) #if ($x > 0) $recur #end #end",
        ImmutableMap.of("x", 5));
    expectException(
        "#define ($hello) $x #end #set ($x = 1) #set ($y = $hello.foo)",
        "$hello comes from #define");
    expectException(
        "#define ($hello) $x #end #set ($x = 1) #set ($y = $hello.foo())",
        "$hello comes from #define");
    expectException(
        "#define ($hello) $x #end #set ($x = 1) #set ($y = $hello[0])",
        "$hello comes from #define");
  }

  @Test
  public void expressions() {
    compare("#set ($x = 1 + 1) $x");
    compare("#set ($x = 1 + 2 * 3) $x");
    compare("#set ($x = (1 + 1 == 2)) $x");
    compare("#set ($x = (1 + 1 != 2)) $x");
    compare("#set ($x = 22 - 7) $x");
    compare("#set ($x = 22 / 7) $x");
    compare("#set ($x = 22 % 7) $x");

    compare("#set ($x = 'foo' + 'bar') $x");
    compare("#set ($x = 23 + ' skidoo') $x");
    compare("#set ($x = 'heaven ' + 17) $x");

    // Check that we copy Velocity here: these null references will be replaced by their source
    // text, for example "$bar" for the null $bar reference here.
    compare("#set ($x = $bar + 'foo') $x", Collections.singletonMap("bar", null));

    // In V2, if $bar is on the LHS of + it is as before, but on the RHS it is replaced by an empty
    // string.
    assume().that(VERSION).isEqualTo(Version.V1);

    compare("#set ($x = 'foo' + $bar) $x", Collections.singletonMap("bar", null));

    // This one results in "foo$bar + $bar" in both Velocity and EscapeVelocity. $bar + $bar is
    // null and then 'foo' + null gets replaced by a representation of the source expression that
    // produced the null.
    compare("#set ($x = 'foo' + ($bar + $bar)) $x", Collections.singletonMap("bar", null));
  }

  @Test
  public void divideByZeroIsNull() {
    Map<String, Object> vars = new TreeMap<>();
    vars.put("null", null);
    Number[] values = {-1, 0, 23, Integer.MAX_VALUE};
    for (Number value : values) {
      vars.put("value", value);
      compare("#set ($x = $value / 0) #if ($x == $null) null #else $x #end", vars);
      compare("#set ($x = $value % 0) #if ($x == $null) null #else $x #end", vars);
    }
  }

  @Test
  public void arithmeticOperationsOnNullAreNull() {
    String template =
        Joiner.on('\n')
            .join(
                "#macro (nulltest $x) #if ($x == $null) is #else not #end null #end",
                "#nulltest($null)",
                "#nulltest('not null')",
                "#set ($x = 1 + $null) #nulltest($x)",
                "#set ($x = $null + $null) #nulltest($x)",
                "#set ($x = $null - 1) #nulltest($x)",
                "#set ($x = $null * $null) #nulltest($x)",
                "#set ($x = $null / $null) #nulltest($x)",
                "#set ($x = 3 / $null) #nulltest($x)",
                "#set ($x = $null / 3) #nulltest($x)");
    compare(template, Collections.singletonMap("null", null));
  }

  @Test
  public void comparisonsOnNullFail() {
    Map<String, Object> vars = new TreeMap<>();
    vars.put("foo", null);
    vars.put("bar", null);
    expectException(
        "#if ($foo < 1) null < 1 #end", vars, "Left operand $foo of < must not be null");
    expectException(
        "#if (1 < $foo) 1 < null #end", vars, "Right operand $foo of < must not be null");
    expectException(
        "#if ($foo < $bar) null < null #end", vars, "Left operand $foo of < must not be null");
    expectException(
        "#if ($foo >= $bar) null >= null #end", vars, "Left operand $foo of >= must not be null");
  }

  @Test
  public void associativity() {
    compare("#set ($x = 3 - 2 - 1) $x");
    compare("#set ($x = 16 / 4 / 4) $x");
  }

  @Test
  public void precedence() {
    compare("#set ($x = 1 + 2 + 3 * 4 * 5 + 6) $x");
    compare("#set($x=1+2+3*4*5+6)$x");
    compare("#set ($x = 1 + 2 * 3 == 3 * 2 + 1) $x");
  }

  @Test
  public void and() {
    compare("#set ($x = false && false) $x");
    compare("#set ($x = false && true) $x");
    compare("#set ($x = true && false) $x");
    compare("#set ($x = true && true) $x");
    compare("#set ($x = false and false) $x");
    compare("#set ($x = false and true) $x");
    compare("#set ($x = true and false) $x");
    compare("#set ($x = true and true) $x");
  }

  @Test
  public void or() {
    compare("#set ($x = false || false) $x");
    compare("#set ($x = false || true) $x");
    compare("#set ($x = true || false) $x");
    compare("#set ($x = true || true) $x");
    compare("#set ($x = false or false) $x");
    compare("#set ($x = false or true) $x");
    compare("#set ($x = true or false) $x");
    compare("#set ($x = true or true) $x");
  }

  @Test
  public void not() {
    compare("#set ($x = !true) $x");
    compare("#set ($x = !false) $x");
    compare("#set ($x = not true) $x");
    compare("#set ($x = not false) $x");
  }

  @Test
  public void misspelledWordOperators() {
    expectException(
        "#if (no true) what #end", "Identifier must be preceded by $ or be true or false");
    expectException(
        "#if (nott true) what #end", "Identifier must be preceded by $ or be true or false");
    expectException("#if (true oor false) what #end", "Expected 'or' but was 'oor");
    expectException("#if (true andd false) what #end", "Expected 'and' but was 'andd");
    expectException("#if (true annd false) what #end", "Expected 'and' but was 'annd");

    // Neither Velocity nor EscapeVelocity has an xor operator.
    expectException("#if (true xor false) what #end", "Expected )");
  }

  @Test
  public void truthValues() {
    compare("#set ($x = $true && true) $x", ImmutableMap.of("true", true));
    compare("#set ($x = $false && true) $x", ImmutableMap.of("false", false));
    compare("#set ($x = $emptyCollection && true) $x",
        ImmutableMap.of("emptyCollection", ImmutableList.of()));
    compare("#set ($x = $emptyString && true) $x", ImmutableMap.of("emptyString", ""));
  }

  @Test
  public void numbers() {
    compare("#set ($x = 0) $x");
    compare("#set ($x = -1) $x");
    compare("#set ($x = " + Integer.MAX_VALUE + ") $x");
    compare("#set ($x = " + Integer.MIN_VALUE + ") $x");
  }

  @Test
  public void listLiterals() {
    compare("#set ($list = []) $list");
    compare("#set ($list = ['a', 'b', 'c']) $list");
    compare("#set ($list = [ 1,2,3 ] ) $list");
    compare("#foreach ($x in [$a, $b]) $x #end", ImmutableMap.of("a", 5, "b", 3));
    compare("#set ($list = [ $null, $null ]) $list.size()", Collections.singletonMap("null", null));
    // Like Velocity, we don't accept general expressions here.
    expectException("#set ($list = [2 + 3])", "Expected ] at end of list literal");
    // Test the toString():
    expectException(
        "$map[[1, 2, 3]]",
        ImmutableMap.of("map", ImmutableMap.of()),
        "Null value for $map[[1, 2, 3]]");
  }

  @Test
  public void rangeLiterals() {
    compare("#set ($range = [1..5]) $range");
    compare("#set ($range = [5 .. 1]) $range");
    compare("#foreach ($x in [-1 .. 5]) $x #end");
    compare("#foreach ($x in [5..-1]) $x #end");
    compare("#foreach ($x in [$a..$b]) $x #end", ImmutableMap.of("a", 3, "b", 5));
    expectException("#set ($range = ['foo'..'bar'])", "Arithmetic is only available on integers");
    // Like Velocity, we don't accept general expressions here.
    expectException("#set ($list = [2 * 3 .. 10])", "Expected ] at end of list literal");
    expectException("#set ($list = [10 .. 2 * 3])", "Expected ] at end of range literal");
    // Test the toString():
    expectException(
        "$map[[1..3]]",
        ImmutableMap.of("map", ImmutableMap.of()),
        "Null value for $map[[1..3]]");
  }

  private static final String[] RELATIONS = {"==", "!=", "<", ">", "<=", ">="};

  @Test
  public void intRelations() {
    int[] numbers = {-1, 0, 1, 17};
    for (String relation : RELATIONS) {
      for (int a : numbers) {
        for (int b : numbers) {
          compare("#set ($x = $a " + relation + " $b) $x",
              ImmutableMap.<String, Object>of("a", a, "b", b));
        }
      }
    }
  }

  @Test
  public void relationPrecedence() {
    compare("#set ($x = 1 < 2 == 2 < 1) $x");
    compare("#set ($x = 2 < 1 == 2 < 1) $x");
  }

  /**
   * Tests the surprising definition of equality mentioned in
   * {@link ExpressionNode.BinaryExpressionNode}.
   */
  @Test
  public void funkyEquals() {
    compare("#set ($t = (123 == \"123\")) $t");
    compare("#set ($f = (123 == \"1234\")) $f");

    // In V2, two objects are equal if they implement CharSequence and their characters are the
    // same.
    assume().that(VERSION).isEqualTo(Version.V1);
    compare("#set ($x = ($sb1 == $sb2)) $x", ImmutableMap.of(
        "sb1", (Object) new StringBuilder("123"),
        "sb2", (Object) new StringBuilder("123")));
  }

  @Test
  public void ifTrueNoElse() {
    compare("x#if (true)y #end z");
    compare("x#if (true)y #end  z");
    compare("x#if (true)y #end\nz");
    compare("x#if (true)y #end\n z");
    compare("x#if (true) y #end\nz");
    compare("x#if (true)\ny #end\nz");
    compare("x#if (true) y #end\nz");
    compare("x#if (true) y #end\n\nz");
    compare("$x #if (true) y #end $x ", ImmutableMap.of("x", "!"));
  }

  @Test
  public void ifFalseNoElse() {
    compare("x#if (false)y #end z");
    compare("x#if (false)y #end\nz");
    compare("x#if (false)y #end\n z");
    compare("x#if (false) y #end\nz");
    compare("x#if (false)\ny #end\nz");
    compare("x#if (false) y #end\nz");
  }

  @Test
  public void ifTrueWithElse() {
    compare("x#if (true) a #else b #end z");
  }

  @Test
  public void ifFalseWithElse() {
    compare("x#if (false) a #else b #end z");
  }

  @Test
  public void ifTrueWithElseIf() {
    compare("x#if (true) a #elseif (true) b #else c #end z");
  }

  @Test
  public void ifFalseWithElseIfTrue() {
    compare("x#if (false) a #elseif (true) b #else c #end z");
    compare("x#if (false)\na\n#elseif (true)\nb\n#else\nc\n#end\nz");
  }

  @Test
  public void ifFalseWithElseIfFalse() {
    compare("x#if (false) a #elseif (false) b #else c #end z");
  }

  @Test
  public void ifBraces() {
    compare("x#{if}(false)a#{elseif}(false)b #{else}c#{end}z");
  }
  @Test
  public void ifUndefined() {
    compare("#if ($undefined) really? #else indeed #end");
    compare("#if ($false || $undefined) nope #else yes #end", ImmutableMap.of("false", false));
    compare("#if ($true && $undefined) nope #else yes #end", ImmutableMap.of("true", true));
    compare("#if (!$undefined) yes #else nope #end");

    // Only plain references get this special treatment and only when being evaluated for truth.
    expectException("#if ($foo.bar) oops #end", "Undefined reference $foo");
    expectException("#if ($foo.bar()) oops #end", "Undefined reference $foo");
    expectException("#if ($foo[0]) oops #end", "Undefined reference $foo");
    expectException(
        "#if ($list[$foo]) oops #end",
        ImmutableMap.of("list", ImmutableList.of("foo", "bar", "baz")),
        "Undefined reference $foo");
    expectException(
        "#if ($undefined1 == $undefined2) yes #else nope #end", "Undefined reference $undefined1");

    // The special treatment is only in #if, not when evaluating Boolean expressions in general.
    expectException("#set ($foo = !$undefined) $foo", "Undefined reference $undefined");
    expectException(
        "#set ($foo = $false || $undefined) $foo",
        ImmutableMap.of("false", false),
        "Undefined reference $undefined");
  }

  @Test
  public void forEach() {
    compare("x#foreach ($x in $c) <$x> #end y",
        ImmutableMap.of("c", ImmutableList.of()));
    compare("x#foreach ($x in $c) <$x> #end\ny",
        ImmutableMap.of("c", ImmutableList.of()));
    compare("x#foreach ($x in $c) <$x> #end\n\ny",
        ImmutableMap.of("c", ImmutableList.of()));
    compare("x#foreach ($x in $c) <$x> #end y",
        ImmutableMap.of("c", ImmutableList.of("foo", "bar", "baz")));
    compare("x#foreach ($x in $c) <$x> #end y",
        ImmutableMap.of("c", new String[] {"foo", "bar", "baz"}));
    compare("x#foreach ($x in $c) <$x> #end y",
        ImmutableMap.of("c", ImmutableMap.of("foo", "bar", "baz", "buh")));
    compare("x#foreach (${x} in $c) <$x> #end y",
        ImmutableMap.of("c", ImmutableMap.of("foo", "bar", "baz", "buh")));
    compare("x#foreach ($!{x} in $c) <$x> #end y",
        ImmutableMap.of("c", ImmutableMap.of("foo", "bar", "baz", "buh")));
  }

  @Test
  public void forEachBad() {
    expectException("#foreach (x in $c) <$x> #end", "Expected variable beginning with '$'");
    expectException("#foreach ($x.foo in $c) <$x> #end", "Expected simple variable");
    expectException("#foreach ($ in $c) #end", "Expected simple variable");
  }

  @Test
  public void forEachNull() {
    // Bizarrely, Velocity allows you to iterate on null, with no effect.
    Map<String, Object> vars = Collections.singletonMap("null", null);
    compare("#foreach ($x in $null) $x #end", vars);
  }

  @Test
  public void forEachHasNext() {
    compare("x#foreach ($x in $c) <$x#if ($foreach.hasNext), #end> #end y",
        ImmutableMap.of("c", ImmutableList.of()));
    compare("x#foreach ($x in $c) <$x#if ($foreach.hasNext), #end> #end y",
        ImmutableMap.of("c", ImmutableList.of("foo", "bar", "baz")));
  }

  @Test
  public void forEachFirstLast() {
    compare("x#foreach ($x in $c) <#if ($foreach.first)?#end$x#if ($foreach.last)!#end#end",
        ImmutableMap.of("c", ImmutableList.of()));
    compare("x#foreach ($x in $c) <#if ($foreach.first)?#end$x#if ($foreach.last)!#end#end",
        ImmutableMap.of("c", ImmutableList.of("foo", "bar", "baz")));
  }

  @Test
  public void nestedForEach() {
    String template =
        "$x #foreach ($x in $listOfLists)\n"
        + "  #foreach ($y in $x)\n"
        + "    ($y)#if ($foreach.hasNext), #end\n"
        + "  #end#if ($foreach.hasNext); #end\n"
        + "#end\n"
        + "$x\n";
    Object listOfLists = ImmutableList.of(
        ImmutableList.of("foo", "bar", "baz"), ImmutableList.of("fred", "jim", "sheila"));
    compare(template, ImmutableMap.of("x", 23, "listOfLists", listOfLists));
  }

  @Test
  public void forEachScope() {
    String template =
        "$x #foreach ($x in $list)\n"
        + "[$x]\n"
        + "#set ($x = \"bar\")\n"
        + "#set ($othervar = \"baz\")\n"
        + "#end\n"
        + "$x $othervar";
    compare(
        template, ImmutableMap.of("x", "foo", "list", ImmutableList.of("blim", "blam", "blum")));
  }

  @Test
  public void forEachIndex() {
    String template =
        "#foreach ($x in $list)"
            + "[$foreach.index]"
            + "#foreach ($y in $list)"
            + "($foreach.index)==$x.$y=="
            + "#end"
            + "#end";
    compare(template, ImmutableMap.of("list", ImmutableList.of("blim", "blam", "blum")));
  }

  @Test
  public void forEachCount() {
    String template =
        "#foreach ($x in $list)"
            + "[$foreach.count]"
            + "#foreach ($y in $list)"
            + "($foreach.count)==$x.$y=="
            + "#end"
            + "#end";
    compare(template, ImmutableMap.of("list", ImmutableList.of("blim", "blam", "blum")));
  }

  @Test
  public void forEachForEach() {
    // There's no reason to do this, but if you do you get {} for $foreach.
    compare("#foreach ($x in [1..10]) [$foreach] #end");
  }

  @Test
  public void breakDirective() {
    compare("#foreach ($x in [1..10]) $x #if ($x == 5) #break #end #end");
    compare("#foreach ($x in [1..10]) $x #if ($x == 5)\n#break\n#end #end");
    compare("#foreach ($x in [1..10]) $x #if ($x == 5)\n#break ($foreach)\n#end #end");
    compare("#foreach ($x in [1..10]) $x #if ($x == 5)\n#break\n($foreach)\nignored#end #end");
    compare("#foreach ($x in [1..10]) $x #set ($f = $foreach) #break($f) #end");
    compare("foo bar #break baz"); // should render as "foo bar ".
    expectException(
        "#foreach ($x in [1..10]) #break($null) #end",
        Collections.singletonMap("null", null),
        "Argument to #break is not a supported scope: $null");
    expectException("foo bar #break($foreach) baz", "Undefined reference $foreach");
    expectException("#set ($x = 17) #break($x)", "Argument to #break is not a supported scope: $x");
    expectException("#foreach ($x in [1..10]) $x #break($foreach #end", "Expected )");
  }

  @Test
  public void setSpacing() {
    // The spacing in the output from #set is eccentric.
    // If the #set is preceded by a reference or a comment or a directive (for example another #set)
    // with only horizontal space intervening, that space is deleted. But if there are newlines,
    // nothing is deleted. But, a newline immediately after a directive or comment is already
    // deleted, and if only horizontal space remains before the #set, it is deleted.
    compare("  #set ($x = 1)"); // preceding space is deleted
    compare("x#set ($x = 0)x");
    compare("x #set ($x = 0)x");
    compare("x #set ($x = 0) x");
    compare("$x#set ($x = 0)x", ImmutableMap.of("x", "!"));
    compare("x#set ($foo = 'bar')\n#set ($baz = 'buh')\n!");
    compare("x#if (1 + 1 == 2) ok #else ? #end\n#set ($foo = 'bar')\ny");
    compare("x#if (1 + 1 == 2) ok #else ? #end  #set ($foo = 'bar')\ny");

    compare("$x  #set ($x = 0)x", ImmutableMap.of("x", "!"));
    compare("$x\n#set ($x = 0)x", ImmutableMap.of("x", "!"));
    compare("#set($x = 0)\n#set($y = 1)\n<$x$y>");
    compare("#set($x = 0)\n  #set($y = 1)\n<$x$y>");
    compare("$x.length()  #set ($x = 0)x", ImmutableMap.of("x", "!"));
    compare("$x.empty  #set ($x = 0)x", ImmutableMap.of("x", "!"));
    compare("$x[0]  #set ($x = 0)x", ImmutableMap.of("x", ImmutableList.of("!")));

    compare("x#set ($x = 0)\n  $x!");

    compare("x  #set($x = 0)  #set($x = 0)  #set($x = 0)  y");

    compare("x ## comment\n  #set($x = 0)  y");
    compare("x #* comment *#    #set($x = 0)  y");
    compare("$list.size()\n#set ($foo = 'bar')\n!", ImmutableMap.of("list", ImmutableList.of()));
    compare("$list[0]\n  #set ($foo = 'bar')\n!", ImmutableMap.of("list", ImmutableList.of("x")));

    compare("  #set ($x = 3)\n");
    compare("\n\n#set ($x = 3)\n");
    compare("\n\n  #set ($x = 3)\n");
    compare(
        "  #foreach ($i in [1..3])\n  #set ($j = \"!$i!\")\n  #set ($k = $i + 1)\n  $j$k\n  #end");

    compare(
        "#set ($foo = 17)\n" //
        + "#set ($bar = 23)\n"
        + "\n"
        + "#set ($baz = 5)\n"
        + "hello");

    compare(
        "## comment\n" //
        + "\n"
        + "\n"
        + "#set ($foo = 17)\n"
        + "hello");

    compare(
      "## comment\n" //
      + "\n"
      + "  #set ($foo = 17)\n"
      + "hello");

    compare(
        "#macro (m)\n\n\n\n" //
        + "#set ($foo = 17)\n"
        + "hello\n"
        + "#end\n"
        + "#m()\n");

    compare(
        "#macro (m)\n" //
        + "  #set ($foo = 17)\n"
        + "hello\n"
        + "#end\n"
        + "#m()\n");
  }


  @Test
  public void simpleMacro() {
    String template =
        "xyz\n"
        + "#macro (m)\n"
        + "hello world\n"
        + "#end\n"
        + "#m() abc #m()\n";
    compare(template);
  }

  @Test
  public void macroWithArgs() {
    String template =
        "$x\n"
        + "#macro (m $x $y)\n"
        + "  #if ($x < $y) less #else greater #end\n"
        + "#end\n"
        + "#m(17 23) #m(23 17) #m(17 17)\n"
        + "$x";
    compare(template, ImmutableMap.of("x", "tiddly"));
  }

  @Test
  public void macroWithCommaSeparatedArgs() {
    String template =
        "$x\n"
        + "#macro (m, $x, $y)\n"
        + "  #if ($x < $y) less #else greater #end\n"
        + "#end\n"
        + "#m(17 23) #m(23 17) #m(17 17)\n"
        + "$x";
    compare(template, ImmutableMap.of("x", "tiddly"));
  }

  @Test
  public void recursiveMacro() {
    String template =
        "#macro (m $s)\n"
            + "$s\n"
            + "  #if (! $s.empty)\n"
            + "    #set ($s = $s.substring(1))\n"
            + "    #m($s)\n"
            + "  #end\n"
            + "#end\n"
            + "#m('foobar')\n";
    compare(template);
  }

  /**
   * Tests defining a macro inside a conditional. This proves that macros are not evaluated in the
   * main control flow, but rather are extracted at parse time. It also tests what happens if there
   * is more than one definition of the same macro. (It is not apparent from the test, but it is the
   * first definition that is retained.)
   */
  @Test
  public void conditionalMacroDefinition() {
    String templateFalse =
        "#if (false)\n"
        + "  #macro (m) foo #end\n"
        + "#else\n"
        + "  #macro (m) bar #end\n"
        + "#end\n"
        + "#m()\n";
    compare(templateFalse);

    String templateTrue =
        "#if (true)\n"
        + "  #macro (m) foo #end\n"
        + "#else\n"
        + "  #macro (m) bar #end\n"
        + "#end\n"
        + "#m()\n";
    compare(templateTrue);
  }

  /**
   * Tests referencing a macro before it is defined. Since macros are extracted at parse time but
   * references are only used at evaluation time, this works.
   */
  @Test
  public void forwardMacroReference() {
    String template =
        "#m(17)\n"
        + "#macro (m $x)\n"
        + "  !$x!\n"
        + "#end";
    compare(template);
  }

  @Test
  public void macroArgsSeparatedBySpaces() {
    String template =
        "#macro (sum $x $y $z)\n"
        + "  #set ($sum = $x + $y + $z)\n"
        + "  $sum\n"
        + "#end\n"
        + "#sum ($list[0] $list.get(1) 5)\n";
    compare(template, ImmutableMap.of("list", ImmutableList.of(3, 4)));
  }

  @Test
  public void macroArgsSeparatedByCommas() {
    String template =
        "#macro (sum $x $y $z)\n"
        + "  #set ($sum = $x + $y + $z)\n"
        + "  $sum\n"
        + "#end\n"
        + "#sum ($list[0],$list.get(1),5)\n";
    compare(template, ImmutableMap.of("list", ImmutableList.of(3, 4)));
  }

  // The following tests are based on http://wiki.apache.org/velocity/MacroEvaluationStrategy.
  // They verify some of the trickier details of Velocity's call-by-name semantics.

  @Test
  public void callBySharing() {
    // The example on the web page is wrong because $map.put('x', 'a') evaluates to null, which
    // Velocity rejects as a render error. We fix this by ensuring that the returned previous value
    // is not null.
    // Here, the value of $y should not be affected by #set($x = "a"), even though the name passed
    // to $x is $y.
    String template =
        "#macro(callBySharing $x $map)\n"
        + "  #set($x = \"a\")\n"
        + "  $map.put(\"x\", \"a\")\n"
        + "#end\n"
        + "#callBySharing($y $map)\n"
        + "y is $y\n"
        + "map[x] is $map[\"x\"]\n";
    Supplier<Map<String, Object>> makeMap = new Supplier<Map<String, Object>>() {
      @Override public Map<String, Object> get() {
        return ImmutableMap.<String, Object>of(
            "y", "y",
            "map", new HashMap<String, Object>(ImmutableMap.of("x", (Object) "foo")));
      }
    };
    compare(template, makeMap);
  }

  @Test
  public void callByMacro() {
    // In V2, macro arguments are call-by-value rather than call-by-name.
    assume().that(VERSION).isEqualTo(Version.V1);
    // Since #callByMacro1 never references its argument, $x.add("t") is never evaluated during it.
    // Since #callByMacro2 references its argument twice, $x.add("t") is evaluated twice during it.
    String template =
        "#macro(callByMacro1 $p)\n"
        + "  not using\n"
        + "#end\n"
        + "#macro(callByMacro2 $p)\n"
        + "  using: $p\n"
        + "  using again: $p\n"
        + "  using again: $p\n"
        + "#end\n"
        + "#callByMacro1($x.add(\"t\"))\n"
        + "x = $x\n"
        + "#callByMacro2($x.add(\"t\"))\n"
        + "x = $x\n";
    Supplier<Map<String, Object>> makeMap = new Supplier<Map<String, Object>>() {
      @Override public Map<String, Object> get() {
        return ImmutableMap.<String, Object>of("x", new ArrayList<Object>());
      }
    };
    compare(template, makeMap);
  }

  @Test
  public void callByValue() {
    // The assignments to the macro parameters $a and $b cause those parameters to be shadowed,
    // so the output is: a b becomes b a.
    String template =
        "#macro(callByValueSwap $a $b)\n"
        + "  $a $b becomes\n"
        + "  #set($tmp = $a)\n"
        + "  #set($a = $b)\n"
        + "  #set($b = $tmp)\n"
        + "  $a $b\n"
        + "#end"
        + "#callByValueSwap(\"a\", \"b\")";
    compare(template);
  }

  // First "Call by macro expansion example" doesn't apply as long as we don't have map literals.

  @Test
  public void nameCaptureSwap() {
    // In V2, macro arguments are call-by-value rather than call-by-name.
    assume().that(VERSION).isEqualTo(Version.V1);

    // Here, the arguments $a and $b are variables rather than literals, which means that their
    // values change when we set those variables. #set($tmp = $a) changes the meaning of $b since
    // $b is the name $tmp. So #set($a = $b) shadows parameter $a with the value of $tmp, which we
    // have just set to "a". Then #set($b = $tmp) shadows parameter $b also with the value of $tmp.
    // The end result is: a b becomes a a.
    String template =
        "#macro(nameCaptureSwap $a $b)\n"
        + "  $a $b becomes\n"
        + "  #set($tmp = $a)\n"
        + "  #set($a = $b)\n"
        + "  #set($b = $tmp)\n"
        + "  $a $b\n"
        + "#end\n"
        + "#set($x = \"a\")\n"
        + "#set($tmp = \"b\")\n"
        + "#nameCaptureSwap($x $tmp)";
    compare(template);
  }

  @Test
  public void badBraceReference() {
    String template = "line 1\nline 2\nbar${foo.!}baz";
    expectException(template, "Expected }, on line 3, at text starting: .!}baz");
  }

  @Test
  public void undefinedMacro() {
    String template = "#oops()";
    expectException(
        template,
        "#oops is neither a standard directive nor a macro that has been defined");
  }

  @Test
  public void macroArgumentMismatch() {
    String template =
        "#macro (twoArgs $a $b) $a $b #end\n"
        + "#twoArgs(23)\n";
    expectException(template, "Wrong number of arguments to #twoArgs: expected 2, got 1");
  }

  @Test
  public void macroWithBody() {
    // The #if ($bodyContent) is needed because Velocity treats $bodyContent as undefined if the
    // macro is invoked as #withBody rather than #@withBody. The documented examples use
    // $!bodyContent but that doesn't work if there is no body and Velocity is in strict ref mode.
    String template =
        "#macro(withBody $x)\n"
        + "$x\n#if ($bodyContent) $bodyContent #end\n"
        + "#end\n"
        + "#withBody('foo')\n"
        + "#@withBody('bar')\n"
        + "#if (0 == 1) what #else yes #end\n"
        + "#end ## end of #@withBody";
    compare(template);
  }

  @Test
  public void nestedMacrosWithBodies() {
    String template =
        "#macro(outer $x)\n"
        + "$x $!bodyContent $x\n"
        + "#end\n"
        + "#macro(inner $x)\n"
        + "[$x] $!bodyContent [$x]\n"
        + "#end\n"
        + "#@outer('foo')\n"
        + "before inner\n"
        + "#@inner('bar')\n"
        + "in inner\n"
        + "#end ## inner\n"
        + "after inner\n"
        + "#end ## outer\n";
    compare(template);
  }

  @Test
  public void bodyContentTwice() {
    String template =
        "#macro(one)\n"
        + "[$bodyContent]\n"
        + "#end\n"
        + "#macro(two)\n"
        + "#set($bodyContentCopy = \"$bodyContent\")\n"
        + "<#@one()$bodyContentCopy#end>\n"
        + "#end\n"
        + "#@two()foo#end\n";
    // Velocity doesn't handle this well. It shouldn't be necessary to make $bodyContentCopy; we
    // should just be able to use $bodyContent. But that gets an exception: "Reference $bodyContent
    // evaluated to object org.apache.velocity.runtime.directive.Block$Reference whose toString()
    // method returned null". By evaluating it into $bodyContentCopy we avoid whatever confusion
    // that was. This is probably related to
    // https://issues.apache.org/jira/projects/VELOCITY/issues/VELOCITY-940.
    compare(template);
  }

  @Test
  public void notMacroCall() {
    // In V2, you don't need () after a parameterless macro call.
    assume().that(VERSION).isEqualTo(Version.V1);
    compare("#@ foo");
    compare("#@foo no parens");
  }

  @Test
  public void unclosedBlockQuote() {
    // This is not an error in V2. https://issues.apache.org/jira/browse/VELOCITY-962
    assume().that(VERSION).isEqualTo(Version.V1);
    String template = "foo\nbar #[[\nblah\nblah";
    expectException(template, "Unterminated #[[ - did not see matching ]]#, on line 2");
  }

  @Test
  public void unclosedBlockComment() {
    compare("foo\nbar #*\nblah\nblah");
  }

  @Test
  public void evaluate() {
    compare("#evaluate('foo $x bar')", ImmutableMap.of("x", "baz"));
    compare("#evaluate('foo #set ($x = 17) $x bar')");
    compare("#evaluate($x) $y", ImmutableMap.of("x", "#set ($y = 'foo')"));
    compare("#set($nested = '#set ($y = \"foo\")')\n"
        + "#evaluate('#evaluate ($nested)')\n"
        + "$y");
    compare("#evaluate ('$x')\nnext line", ImmutableMap.of("x", "foo"));
    compare("#evaluate($null)", Collections.singletonMap("null", null));
    expectException("#evaluate(23)", "Argument to #evaluate must be a string: 23");
  }

  @Test
  public void nullReference() throws IOException {
    Map<String, Object> vars = Collections.singletonMap("foo", null);
    expectException("==$foo==", vars, "Null value for $foo");
    compare("==$!foo==", vars);
  }

  @Test
  public void nullMethodCall() throws IOException {
    Map<String, Object> vars = ImmutableMap.of("map", ImmutableMap.of());
    expectException("==$map.get(23)==", vars, "Null value for $map.get(23)");
    compare("==$!map.get(23)==", vars);
  }

  @Test
  public void nullIndex() throws IOException {
    Map<String, Object> vars = ImmutableMap.of("map", ImmutableMap.of());
    expectException("==$map[23]==", vars, "Null value for $map[23]");
    compare("==$!map[23]==", vars);
  }

  @Test
  public void ifNull() throws IOException {
    // Null references in #if mean false.
    Map<String, Object> vars = Collections.singletonMap("nullRef", null);
    compare("#if ($nullRef) oops #end", vars);
  }

  @Test
  public void nullProperty() throws IOException {
    // We use a LinkedList with a null element so that list.getFirst() will return null. Then
    // $list.first is a null reference.
    @SuppressWarnings("JdkObsolete")
    LinkedList<String> list = new LinkedList<>();
    list.add(null);
    Map<String, Object> vars = ImmutableMap.of("list", list);
    expectException("==$list.first==", vars, "Null value for $list.first");
    compare("==$!list.first==", vars);
  }

  @Test
  public void silentRefInDirective() throws IOException {
    Map<String, Object> vars = new TreeMap<>();
    vars.put("null", null);
    compare("#if ($!null == '') yes #end", vars);
  }

  @Test
  public void silentRefInString() throws IOException {
    Map<String, Object> vars = Collections.singletonMap("null", null);
    compare("#set ($nuller = \"$!{null}er\") $nuller", vars);
  }

  private String renderWithResources(
      String templateResourceName,
      ImmutableMap<String, String> resourceMap,
      Map<String, String> vars) {
    String name = testName.getMethodName();
    VelocityEngine engine = newVelocityEngine();
    engine.setProperty("resource.loader", "string");
    engine.setProperty("string.resource.loader.class", StringResourceLoader.class.getName());
    engine.setProperty("string.resource.loader.repository.name", name);
    engine.init();
    StringResourceRepository repo = StringResourceLoader.getRepository(name);
    resourceMap.forEach(repo::putStringResource);
    StringWriter velocityWriter = new StringWriter();
    VelocityContext velocityContext = new VelocityContext(new TreeMap<>(vars));
    boolean rendered =
        engine.mergeTemplate(templateResourceName, "UTF-8", velocityContext, velocityWriter);
    assertThat(rendered).isTrue();
    return velocityWriter.toString();
  }

  @Test
  public void parseDirective() throws IOException {
    // If outer.vm does #parse("nested.vm"), then we should be able to #set a variable in
    // nested.vm and use it in outer.vm, and we should be able to define a #macro in nested.vm
    // and call it in outer.vm. However, if a macro is defined in outer.vm then a later definition
    // in nested.vm will be ignored.
    ImmutableMap<String, String> resources = ImmutableMap.of(
        "outer.vm",
        "first line\n"
            + "#macro (alreadyDefined) already defined #end\n"
            + "#parse (\"nested.vm\")\n"
            + "#alreadyDefined()\n"
            + "<#decorate (\"left\" \"right\")>\n"
            + "$baz skidoo\n"
            + "last line\n",
        "nested.vm",
        "nested template first line\n"
            + "[#if ($foo == $bar) equal #else not equal #end]\n"
            + "#macro (decorate $a $b) < $a | $b > #end\n"
            + "#macro (alreadyDefined) not redefined #end\n"
            + "#alreadyDefined()\n"
            + "#set ($baz = 23)\n"
            + "nested template last line\n");

    ImmutableMap<String, String> vars = ImmutableMap.of("foo", "foovalue", "bar", "barvalue");

    String velocityResult = renderWithResources("outer.vm", resources, vars);

    Template.ResourceOpener resourceOpener = resourceName -> {
      String resource = resources.get(resourceName);
      if (resource == null) {
        throw new FileNotFoundException(resourceName);
      }
      return new StringReader(resource);
    };
    Template template = Template.parseFrom("outer.vm", resourceOpener);

    String result = template.evaluate(vars);
    assertThat(result).isEqualTo(velocityResult);

    ImmutableMap<String, String> badVars = ImmutableMap.of("foo", "foovalue");
    try {
      template.evaluate(badVars);
      fail();
    } catch (EvaluationException e) {
      assertThat(e).hasMessageThat().isEqualTo(
          "In expression on line 2 of nested.vm: Undefined reference $bar");
    }

    // If we evaluate just nested.vm, we should see it use its own definition of #alreadyDefined,
    // which went unused above because the one in outer.vm took precedence.
    compare(resources.get("nested.vm"), ImmutableMap.of("foo", "foovalue", "bar", "barvalue"));
  }

  @Test
  public void parseDirectiveWithExpression() throws IOException {
    // This tests evaluating the same template with different variables, where the variables
    // determine which other template should be included by #parse. We should notably see that the
    // macros defined differ on each evaluation, since the included templates have different
    // definitions for the #decorate macro.
    ImmutableMap<String, String> resources = ImmutableMap.of(
        "outer.vm",
        "first line\n"
            + "#parse (\"${nested}.vm\")\n"
            + "<#decorate (\"left\" \"right\")>\n"
            + "$baz skidoo\n"
            + "last line\n",
        "nested.vm",
        "nested template first line\n"
            + "[#if ($foo == $bar) equal #else not equal #end]\n"
            + "#macro (decorate $a $b) < $a | $b > #end\n"
            + "#set ($baz = 23)\n"
            + "nested template last line\n",
         "othernested.vm",
         "#macro (decorate $a $b) [ $a | $b ] #end\n"
             + "#set ($baz = 17)\n"
             + "#break\n" // this breaks from the #parse of othernested.vm
             + "not included");
    ImmutableMap<String, String> vars = ImmutableMap.of(
        "foo", "foovalue", "bar", "barvalue", "nested", "nested");

    String velocityResult = renderWithResources("outer.vm", resources, vars);

    Set<String> openedResources = new TreeSet<>();
    Template.ResourceOpener resourceOpener = resourceName -> {
      assertThat(resources).containsKey(resourceName);
      assertThat(openedResources).doesNotContain(resourceName);
      String resource = resources.get(resourceName);
      openedResources.add(resourceName);
      return new StringReader(resource);
    };
    Template template = Template.parseFrom("outer.vm", resourceOpener);

    String result = template.evaluate(vars);
    assertThat(result).isEqualTo(velocityResult);

    Map<String, String> newVars = new LinkedHashMap<>(vars);
    newVars.put("nested", "othernested");
    String newVelocityResult = renderWithResources("outer.vm", resources, newVars);
    String newResult = template.evaluate(newVars);
    assertThat(newResult).isEqualTo(newVelocityResult);

    // This should not read othernested.vm again (the second assertion in resourceOpener above).
    String newResultAgain = template.evaluate(newVars);
    assertThat(newResultAgain).isEqualTo(newResult);
  }

  @Test
  public void parseDirectiveWithParseException() throws IOException {
    ImmutableMap<String, String> resources =
        ImmutableMap.of("outer.vm", "#parse('bad.vm')", "bad.vm", "#end");
    Template.ResourceOpener resourceOpener =
        resourceName -> new StringReader(resources.get(resourceName));
    Template template = Template.parseFrom("outer.vm", resourceOpener);
    EvaluationException e =
        assertThrows(EvaluationException.class, () -> template.evaluate(ImmutableMap.of()));
    assertThat(e).hasCauseThat().isInstanceOf(ParseException.class);
  }

  @Test
  public void parseDirectiveWithIoException() throws IOException {
    ImmutableMap<String, String> resources = ImmutableMap.of("outer.vm", "#parse('bad.vm')");
    Template.ResourceOpener resourceOpener =
        resourceName -> {
          String resource = resources.get(resourceName);
          if (resource == null) {
            throw new FileNotFoundException(resourceName);
          }
          return new StringReader(resource);
        };
    Template template = Template.parseFrom("outer.vm", resourceOpener);
    EvaluationException e =
        assertThrows(EvaluationException.class, () -> template.evaluate(ImmutableMap.of()));
    assertThat(e).hasCauseThat().isInstanceOf(FileNotFoundException.class);
    assertThat(e).hasCauseThat().hasMessageThat().isEqualTo("bad.vm");
  }
}
