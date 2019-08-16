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

import com.google.common.base.CharMatcher;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.primitives.Chars;
import com.google.common.primitives.Ints;
import com.google.escapevelocity.DirectiveNode.ForEachNode;
import com.google.escapevelocity.DirectiveNode.IfNode;
import com.google.escapevelocity.DirectiveNode.SetNode;
import com.google.escapevelocity.ExpressionNode.BinaryExpressionNode;
import com.google.escapevelocity.ExpressionNode.NotExpressionNode;
import com.google.escapevelocity.ReferenceNode.IndexReferenceNode;
import com.google.escapevelocity.ReferenceNode.MemberReferenceNode;
import com.google.escapevelocity.ReferenceNode.MethodReferenceNode;
import com.google.escapevelocity.ReferenceNode.PlainReferenceNode;
import com.google.escapevelocity.StopNode.ElseIfNode;
import com.google.escapevelocity.StopNode.ElseNode;
import com.google.escapevelocity.StopNode.EndNode;
import com.google.escapevelocity.StopNode.EofNode;
import java.io.IOException;
import java.io.LineNumberReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * A parser that reads input from the given {@link Reader} and parses it to produce a
 * {@link Template}.
 *
 * @author emcmanus@google.com (Éamonn McManus)
 */
class Parser {
  private static final int EOF = -1;

  private static final ImmutableSet<Class<? extends StopNode>> EOF_CLASS =
      ImmutableSet.of(EofNode.class);
  private static final ImmutableSet<Class<? extends StopNode>> END_CLASS =
      ImmutableSet.of(EndNode.class);
  private static final ImmutableSet<Class<? extends StopNode>> ELSE_ELSEIF_END_CLASSES =
      ImmutableSet.of(ElseNode.class, ElseIfNode.class, EndNode.class);

  private final LineNumberReader reader;
  private final String resourceName;
  private final Template.ResourceOpener resourceOpener;
  private final Map<String, Macro> macros;

  /**
   * The invariant of this parser is that {@code c} is always the next character of interest.
   * This means that we almost never have to "unget" a character by reading too far. For example,
   * after we parse an integer, {@code c} will be the first character after the integer, which is
   * exactly the state we will be in when there are no more digits.
   *
   * <p>Sometimes we need to read two characters ahead, and in that case we use {@link #pushback}.
   */
  private int c;

  /**
   * A single character of pushback. If this is not negative, the {@link #next()} method will
   * return it instead of reading a character.
   */
  private int pushback = -1;

  Parser(Reader reader, String resourceName, Template.ResourceOpener resourceOpener)
      throws IOException {
    this(reader, resourceName, resourceOpener, new TreeMap<>());
  }

  private Parser(
      Reader reader,
      String resourceName,
      Template.ResourceOpener resourceOpener,
      Map<String, Macro> macros)
      throws IOException {
    this.reader = new LineNumberReader(reader);
    this.reader.setLineNumber(1);
    next();
    this.resourceName = resourceName;
    this.resourceOpener = resourceOpener;
    this.macros = macros;
  }

  /**
   * Parse the input completely to produce a {@link Template}. We use a fairly standard
   * recursive-descent parser with ad-hoc lexing and a few hacks needed to reproduce quirks of
   * Velocity's behaviour.
   */
  Template parse() throws IOException {
    ParseResult parseResult = parseToStop(EOF_CLASS, () -> "outside any construct");
    Node root = Node.cons(resourceName, lineNumber(), parseResult.nodes);
    return new Template(root, ImmutableMap.copyOf(macros));
  }

  private int lineNumber() {
    return reader.getLineNumber();
  }

  /**
   * Gets the next character from the reader and assigns it to {@code c}. If there are no more
   * characters, sets {@code c} to {@link #EOF} if it is not already.
   */
  private void next() throws IOException {
    if (c != EOF) {
      if (pushback < 0) {
        c = reader.read();
      } else {
        c = pushback;
        pushback = -1;
      }
    }
  }

  /**
   * Saves the current character {@code c} to be read again, and sets {@code c} to the given
   * {@code c1}. Suppose the text contains {@code xy} and we have just read {@code y}.
   * So {@code c == 'y'}. Now if we execute {@code pushback('x')}, we will have
   * {@code c == 'x'} and the next call to {@link #next()} will set {@code c == 'y'}. Subsequent
   * calls to {@code next()} will continue reading from {@link #reader}. So the pushback
   * essentially puts us back in the state we were in before we read {@code y}.
   */
  private void pushback(int c1) {
    pushback = c;
    c = c1;
  }

  /**
   * If {@code c} is a space character, keeps reading until {@code c} is a non-space character or
   * there are no more characters.
   */
  private void skipSpace() throws IOException {
    while (Character.isWhitespace(c)) {
      next();
    }
  }

  /**
   * Gets the next character from the reader, and if it is a space character, keeps reading until
   * a non-space character is found.
   */
  private void nextNonSpace() throws IOException {
    next();
    skipSpace();
  }

  /**
   * Skips any space in the reader, and then throws an exception if the first non-space character
   * found is not the expected one. Sets {@code c} to the first character after that expected one.
   */
  private void expect(char expected) throws IOException {
    skipSpace();
    if (c == expected) {
      next();
    } else {
      throw parseException("Expected " + expected);
    }
  }

  private static class ParseResult {
    final ImmutableList<Node> nodes;
    final StopNode stop;

    ParseResult(ImmutableList<Node> nodes, StopNode stop) {
      this.nodes = nodes;
      this.stop = stop;
    }
  }

  /**
   * Parse until reaching a {@code StopNode}. The {@code StopNode} must have one of the classes in
   * {@code stopClasses}. This method is called recursively to parse nested constructs. At the
   * top level, we expect the parse to end when it reaches {@code EofNode}. In a {@code #foreach},
   * for example, we expect the parse to end when it reaches the matching {@code #end}. In an
   * {@code #if}, the parse can end with {@code #end}, {@code #else}, or {@code #elseif}. And then
   * after {@code #else} or {@code #elseif} we will call this method again to parse the next part.
   *
   * @return the nodes that were parsed, plus the {@code StopNode} that caused parsing to stop.
   */
  private ParseResult parseToStop(
      ImmutableSet<Class<? extends StopNode>> stopClasses, Supplier<String> contextDescription)
      throws IOException {
    List<Node> nodes = new ArrayList<>();
    Node node;
    while (true) {
      node = parseNode();
      if (node instanceof StopNode) {
        break;
      }
      if (node instanceof SetNode) {
        SetSpacing.removeSpaceBeforeSet(nodes);
      }
      nodes.add(node);
    }
    StopNode stop = (StopNode) node;
    if (!stopClasses.contains(stop.getClass())) {
      throw parseException("Found " + stop.name() + " " + contextDescription.get());
    }
    return new ParseResult(ImmutableList.copyOf(nodes), stop);
  }

  /**
   * Skip the current character if it is a newline, then parse until reaching a {@code StopNode}.
   * This is used after directives like {@code #if}, where a newline is ignored after the final
   * {@code )} in {@code #if (condition)}.
   */
  private ParseResult skipNewlineAndParseToStop(
      ImmutableSet<Class<? extends StopNode>> stopClasses, Supplier<String> contextDescription)
      throws IOException {
    if (c == '\n') {
      next();
    }
    return parseToStop(stopClasses, contextDescription);
  }

  /** Parses a single node from the reader. */
  private Node parseNode() throws IOException {
    if (c == '#') {
      next();
      switch (c) {
        case '#':
          return parseLineComment();
        case '*':
          return parseBlockComment();
        case '[':
          return parseHashSquare();
        case '{':
          return parseDirective();
        default:
          if (isAsciiLetter(c)) {
            return parseDirective();
          } else {
            // For consistency with Velocity, we treat # not followed by a letter or one of the
            // characters above as a plain character, and we treat #$foo as a literal # followed by
            // the reference $foo.
            return parsePlainText('#');
          }
      }
    }
    if (c == EOF) {
      return new EofNode(resourceName, lineNumber());
    }
    return parseNonDirective();
  }

  private Node parseHashSquare() throws IOException {
    // We've just seen #[ which might be the start of a #[[quoted block]]#. If the next character
    // is not another [ then it's not a quoted block, but it *is* a literal #[ followed by whatever
    // that next character is.
    assert c == '[';
    next();
    if (c != '[') {
      return parsePlainText(new StringBuilder("#["));
    }
    int startLine = lineNumber();
    next();
    StringBuilder sb = new StringBuilder();
    while (true) {
      if (c == EOF) {
        throw new ParseException(
            "Unterminated #[[ - did not see matching ]]#", resourceName, startLine);
      }
      if (c == '#') {
        // This might be the last character of ]]# or it might just be a random #.
        int len = sb.length();
        if (len > 1 && sb.charAt(len - 1) == ']' && sb.charAt(len - 2) == ']') {
          next();
          break;
        }
      }
      sb.append((char) c);
      next();
    }
    String quoted = sb.substring(0, sb.length() - 2);
    return new ConstantExpressionNode(resourceName, lineNumber(), quoted);
  }

  /**
   * Parses a single non-directive node from the reader. This is either a reference, like
   * {@code foo} or {@code bar.baz} or {@code foo.bar[$baz].buh()}; or it is text containing
   * neither references (no {@code $}) nor directives (no {@code #}).
   */
  private Node parseNonDirective() throws IOException {
    if (c == '$') {
      next();
      if (isAsciiLetter(c) || c == '{') {
        return parseReference();
      } else {
        return parsePlainText('$');
      }
    } else {
      int firstChar = c;
      next();
      return parsePlainText(firstChar);
    }
  }

  /**
   * Parses a single directive token from the reader. Directives can be spelled with or without
   * braces, for example {@code #if} or {@code #{if}}. In the case of {@code #end}, {@code #else},
   * and {@code #elseif}, we return a {@link StopNode} representing just the token itself. In other
   * cases we also parse the complete directive, for example a complete {@code #foreach...#end}.
   */
  private Node parseDirective() throws IOException {
    String directive;
    if (c == '{') {
      next();
      directive = parseId("Directive inside #{...}");
      expect('}');
    } else {
      directive = parseId("Directive");
    }
    Node node;
    switch (directive) {
      case "end":
        node = new EndNode(resourceName, lineNumber());
        break;
      case "if":
        return parseIfOrElseIf("#if");
      case "elseif":
        node = new ElseIfNode(resourceName, lineNumber());
        break;
      case "else":
        node = new ElseNode(resourceName, lineNumber());
        break;
      case "foreach":
        return parseForEach();
      case "set":
        node = parseSet();
        break;
      case "parse":
        node = parseParse();
        break;
      case "macro":
        return parseMacroDefinition();
      default:
        node = parsePossibleMacroCall(directive);
    }
    // Velocity skips a newline after any directive. In the case of #if etc, we'll have done this
    // when we stopped scanning the body at #end, so in those cases we return directly rather than
    // breaking into the code here.
    // TODO(emcmanus): in fact it also skips space before the newline, which should be implemented.
    if (c == '\n') {
      next();
    }
    return node;
  }

  /**
   * Parses an {@code #if} construct, or an {@code #elseif} within one.
   *
   * <pre>{@code
   * #if ( <condition> ) <true-text> #end
   * #if ( <condition> ) <true-text> #else <false-text> #end
   * #if ( <condition1> ) <text1> #elseif ( <condition2> ) <text2> #else <text3> #end
   * }</pre>
   */
  private Node parseIfOrElseIf(String directive) throws IOException {
    int startLine = lineNumber();
    expect('(');
    ExpressionNode condition = parseExpression();
    expect(')');
    ParseResult parsedTruePart =
        skipNewlineAndParseToStop(
            ELSE_ELSEIF_END_CLASSES,
            () -> "parsing " + directive + " starting on line " + startLine);
    Node truePart = Node.cons(resourceName, startLine, parsedTruePart.nodes);
    Node falsePart;
    if (parsedTruePart.stop instanceof EndNode) {
      falsePart = Node.emptyNode(resourceName, lineNumber());
    } else if (parsedTruePart.stop instanceof ElseIfNode) {
      falsePart = parseIfOrElseIf("#elseif");
    } else {
      int elseLine = lineNumber();
      ParseResult parsedFalsePart =
          parseToStop(END_CLASS, () -> "parsing #else starting on line " + elseLine);
      falsePart = Node.cons(resourceName, elseLine, parsedFalsePart.nodes);
    }
    return new IfNode(resourceName, startLine, condition, truePart, falsePart);
  }

  /**
   * Parses a {@code #foreach} token from the reader.
   *
   * <pre>{@code
   * #foreach ( $<id> in <expression> ) <body> #end
   * }</pre>
   */
  private Node parseForEach() throws IOException {
    int startLine = lineNumber();
    expect('(');
    expect('$');
    String var = parseId("For-each variable");
    skipSpace();
    boolean bad = false;
    if (c != 'i') {
      bad = true;
    } else {
      next();
      if (c != 'n') {
        bad = true;
      }
    }
    if (bad) {
      throw parseException("Expected 'in' for #foreach");
    }
    next();
    ExpressionNode collection = parseExpression();
    expect(')');
    ParseResult parsedBody =
        skipNewlineAndParseToStop(
            END_CLASS, () -> "parsing #foreach starting on line " + startLine);
    Node body = Node.cons(resourceName, startLine, parsedBody.nodes);
    return new ForEachNode(resourceName, startLine, var, collection, body);
  }

  /**
   * Parses a {@code #set} token from the reader.
   *
   * <pre>{@code
   * #set ( $<id> = <expression> )
   * }</pre>
   */
  private Node parseSet() throws IOException {
    expect('(');
    expect('$');
    String var = parseId("#set variable");
    expect('=');
    ExpressionNode expression = parseExpression();
    expect(')');
    return new SetNode(var, expression);
  }

  /**
   * Parses a {@code #parse} token from the reader.
   *
   * <pre>{@code
   * #parse ( <string-literal> )
   * }</pre>
   *
   * <p>The way this works is inconsistent with Velocity. In Velocity, the {@code #parse} directive
   * is evaluated when it is encountered during template evaluation. That means that the argument
   * can be a variable, and it also means that you can use {@code #if} to choose whether or not
   * to do the {@code #parse}. Neither of those is true in EscapeVelocity. The contents of the
   * {@code #parse} are integrated into the containing template pretty much as if they had been
   * written inline. That also means that EscapeVelocity allows forward references to macros
   * inside {@code #parse} directives, which Velocity does not.
   */
  private Node parseParse() throws IOException {
    int startLine = lineNumber();
    expect('(');
    skipSpace();
    if (c != '"' && c != '\'') {
      throw parseException("#parse only supported with string literal argument");
    }
    ExpressionNode nestedResourceNameExpression = parseStringLiteral(c, false);
    String nestedResourceName = nestedResourceNameExpression.evaluate(null).toString();
    expect(')');
    try (Reader nestedReader = resourceOpener.openResource(nestedResourceName)) {
      Parser nestedParser = new Parser(nestedReader, nestedResourceName, resourceOpener, macros);
      ParseResult parseResult = nestedParser.parseToStop(EOF_CLASS, () -> "outside any construct");
      return Node.cons(resourceName, startLine, parseResult.nodes);
    }
  }

  /**
   * Parses a {@code #macro} token from the reader.
   *
   * <pre>{@code
   * #macro ( <id> $<param1> $<param2> <...>) <body> #end
   * }</pre>
   *
   * <p>Macro parameters are optionally separated by commas.
   */
  private Node parseMacroDefinition() throws IOException {
    int startLine = lineNumber();
    expect('(');
    skipSpace();
    String name = parseId("Macro name");
    ImmutableList.Builder<String> parameterNames = ImmutableList.builder();
    while (true) {
      skipSpace();
      if (c == ')') {
        next();
        break;
      }
      if (c == ',') {
        next();
        skipSpace();
      }
      if (c != '$') {
        throw parseException("Macro parameters should look like $name");
      }
      next();
      parameterNames.add(parseId("Macro parameter name"));
    }
    ParseResult parsedBody =
        skipNewlineAndParseToStop(END_CLASS, () -> "parsing #macro starting on line " + startLine);
    if (!macros.containsKey(name)) {
      ImmutableList<Node> bodyNodes =
          ImmutableList.copyOf(SetSpacing.removeInitialSpaceBeforeSet(parsedBody.nodes));
      Node body = Node.cons(resourceName, startLine, bodyNodes);
      Macro macro = new Macro(startLine, name, parameterNames.build(), body);
      macros.put(name, macro);
    }
    return Node.emptyNode(resourceName, lineNumber());
  }

  /**
   * Parses an identifier after {@code #} that is not one of the standard directives. The assumption
   * is that it is a call of a macro that is defined in the template. Macro definitions are
   * extracted from the template during the second parsing phase (and not during evaluation of the
   * template as you might expect). This means that a macro can be called before it is defined.
   * <pre>{@code
   * #<id> ()
   * #<id> ( <expr1> )
   * #<id> ( <expr1> <expr2>)
   * #<id> ( <expr1> , <expr2>)
   * ...
   * }</pre>
   */
  private Node parsePossibleMacroCall(String directive) throws IOException {
    skipSpace();
    if (c != '(') {
      throw parseException("Unrecognized directive #" + directive);
    }
    next();
    ImmutableList.Builder<ExpressionNode> parameterNodes = ImmutableList.builder();
    while (true) {
      skipSpace();
      if (c == ')') {
        next();
        break;
      }
      parameterNodes.add(parsePrimary());
      if (c == ',') {
        // The documentation doesn't say so, but you can apparently have an optional comma in
        // macro calls.
        next();
      }
    }
    return new DirectiveNode.MacroCallNode(
        resourceName, lineNumber(), directive, parameterNodes.build());
  }

  /**
   * Parses a line comment, which is {@code ##} followed by any number of characters
   * up to and including the next newline.
   */
  private Node parseLineComment() throws IOException {
    int lineNumber = lineNumber();
    while (c != '\n' && c != EOF) {
      next();
    }
    next();
    return new CommentNode(resourceName, lineNumber);
  }

  /**
   * Parses a block comment, which is {@code #*} followed by everything up to and
   * including the next {@code *#}.
   */
  private Node parseBlockComment() throws IOException {
    assert c == '*';
    int startLine = lineNumber();
    int lastC = '\0';
    next();
    // Consistently with Velocity, we do not make it an error if a #* comment is not closed.
    while (!(lastC == '*' && c == '#') && c != EOF) {
      lastC = c;
      next();
    }
    next(); // this may read EOF twice, which works
    return new CommentNode(resourceName, startLine);
  }

  /**
   * A node in the parse tree representing a comment. The only reason for recording comment nodes is
   * so that we can skip space between a comment and a following {@code #set}, to be compatible with
   * Velocity behaviour.
   */
  static class CommentNode extends Node {
    CommentNode(String resourceName, int lineNumber) {
      super(resourceName, lineNumber);
    }

    @Override
    void render(EvaluationContext context, StringBuilder output) {}
  }

  /**
   * Parses plain text, which is text that contains neither {@code $} nor {@code #}. The given
   * {@code firstChar} is the first character of the plain text, and {@link #c} is the second
   * (if the plain text is more than one character).
   */
  private Node parsePlainText(int firstChar) throws IOException {
    StringBuilder sb = new StringBuilder();
    sb.appendCodePoint(firstChar);
    return parsePlainText(sb);
  }

  private Node parsePlainText(StringBuilder sb) throws IOException {
    literal:
    while (true) {
      switch (c) {
        case EOF:
        case '$':
        case '#':
          break literal;
        default:
          // Just some random character.
      }
      sb.appendCodePoint(c);
      next();
    }
    return new ConstantExpressionNode(resourceName, lineNumber(), sb.toString());
  }

  /**
   * Parses a reference, which is everything that can start with a {@code $}. References can
   * optionally be enclosed in braces, so {@code $x} and {@code ${x}} are the same. Braces are
   * useful when text after the reference would otherwise be parsed as part of it. For example,
   * {@code ${x}y} is a reference to the variable {@code $x}, followed by the plain text {@code y}.
   * Of course {@code $xy} would be a reference to the variable {@code $xy}.
   * <pre>{@code
   * <reference> -> $<reference-no-brace> |
   *                ${<reference-no-brace>}
   * }</pre>
   *
   * <p>On entry to this method, {@link #c} is the character immediately after the {@code $}.
   */
  private Node parseReference() throws IOException {
    if (c == '{') {
      next();
      if (!isAsciiLetter(c)) {
        return parsePlainText(new StringBuilder("${"));
      }
      ReferenceNode node = parseReferenceNoBrace();
      expect('}');
      return node;
    } else {
      return parseReferenceNoBrace();
    }
  }

  /**
   * Same as {@link #parseReference()}, except it really must be a reference. A {@code $} in
   * normal text doesn't start a reference if it is not followed by an identifier. But in an
   * expression, for example in {@code #if ($x == 23)}, {@code $} must be followed by an
   * identifier.
   */
  private ReferenceNode parseRequiredReference() throws IOException {
    if (c == '{') {
      next();
      ReferenceNode node = parseReferenceNoBrace();
      expect('}');
      return node;
    } else {
      return parseReferenceNoBrace();
    }
  }

  /**
   * Parses a reference, in the simple form without braces.
   * <pre>{@code
   * <reference-no-brace> -> <id><reference-suffix>
   * }</pre>
   */
  private ReferenceNode parseReferenceNoBrace() throws IOException {
    String id = parseId("Reference");
    ReferenceNode lhs = new PlainReferenceNode(resourceName, lineNumber(), id);
    return parseReferenceSuffix(lhs);
  }

  /**
   * Parses the modifiers that can appear at the tail of a reference.
   * <pre>{@code
   * <reference-suffix> -> <empty> |
   *                       <reference-member> |
   *                       <reference-index>
   * }</pre>
   *
   * @param lhs the reference node representing the first part of the reference
   *     {@code $x} in {@code $x.foo} or {@code $x.foo()}, or later {@code $x.y} in {@code $x.y.z}.
   */
  private ReferenceNode parseReferenceSuffix(ReferenceNode lhs) throws IOException {
    switch (c) {
      case '.':
        return parseReferenceMember(lhs);
      case '[':
        return parseReferenceIndex(lhs);
      default:
        return lhs;
    }
  }

  /**
   * Parses a reference member, which is either a property reference like {@code $x.y} or a method
   * call like {@code $x.y($z)}.
   * <pre>{@code
   * <reference-member> -> .<id><reference-property-or-method><reference-suffix>
   * <reference-property-or-method> -> <id> |
   *                                   <id> ( <method-parameter-list> )
   * }</pre>
   *
   * @param lhs the reference node representing what appears to the left of the dot, like the
   *     {@code $x} in {@code $x.foo} or {@code $x.foo()}.
   */
  private ReferenceNode parseReferenceMember(ReferenceNode lhs) throws IOException {
    assert c == '.';
    next();
    if (!isAsciiLetter(c)) {
      // We've seen something like `$foo.!`, so it turns out it's not a member after all.
      pushback('.');
      return lhs;
    }
    String id = parseId("Member");
    ReferenceNode reference;
    if (c == '(') {
      reference = parseReferenceMethodParams(lhs, id);
    } else {
      reference = new MemberReferenceNode(lhs, id);
    }
    return parseReferenceSuffix(reference);
  }

  /**
   * Parses the parameters to a method reference, like {@code $foo.bar($a, $b)}.
   * <pre>{@code
   * <method-parameter-list> -> <empty> |
   *                            <non-empty-method-parameter-list>
   * <non-empty-method-parameter-list> -> <expression> |
   *                                      <expression> , <non-empty-method-parameter-list>
   * }</pre>
   *
   * @param lhs the reference node representing what appears to the left of the dot, like the
   *     {@code $x} in {@code $x.foo()}.
   */
  private ReferenceNode parseReferenceMethodParams(ReferenceNode lhs, String id)
      throws IOException {
    assert c == '(';
    nextNonSpace();
    ImmutableList.Builder<ExpressionNode> args = ImmutableList.builder();
    if (c != ')') {
      args.add(parseExpression());
      while (c == ',') {
        nextNonSpace();
        args.add(parseExpression());
      }
      if (c != ')') {
        throw parseException("Expected )");
      }
    }
    assert c == ')';
    next();
    return new MethodReferenceNode(lhs, id, args.build());
  }

  /**
   * Parses an index suffix to a method, like {@code $x[$i]}.
   * <pre>{@code
   * <reference-index> -> [ <expression> ]
   * }</pre>
   *
   * @param lhs the reference node representing what appears to the left of the dot, like the
   *     {@code $x} in {@code $x[$i]}.
   */
  private ReferenceNode parseReferenceIndex(ReferenceNode lhs) throws IOException {
    assert c == '[';
    next();
    ExpressionNode index = parseExpression();
    if (c != ']') {
      throw parseException("Expected ]");
    }
    next();
    ReferenceNode reference = new IndexReferenceNode(lhs, index);
    return parseReferenceSuffix(reference);
  }

  enum Operator {
    /**
     * A dummy operator with low precedence. When parsing subexpressions, we always stop when we
     * reach an operator of lower precedence than the "current precedence". For example, when
     * parsing {@code 1 + 2 * 3 + 4}, we'll stop parsing the subexpression {@code * 3 + 4} when
     * we reach the {@code +} because it has lower precedence than {@code *}. This dummy operator,
     * then, behaves like {@code +} when the minimum precedence is {@code *}. We also return it
     * if we're looking for an operator and don't find one. If this operator is {@code ⊙}, it's as
     * if our expressions are bracketed with it, like {@code ⊙ 1 + 2 * 3 + 4 ⊙}.
     */
    STOP("", 0),

    // If a one-character operator is a prefix of a two-character operator, like < and <=, then
    // the one-character operator must come first.
    OR("||", 1),
    AND("&&", 2),
    EQUAL("==", 3), NOT_EQUAL("!=", 3),
    LESS("<", 4), LESS_OR_EQUAL("<=", 4), GREATER(">", 4), GREATER_OR_EQUAL(">=", 4),
    PLUS("+", 5), MINUS("-", 5),
    TIMES("*", 6), DIVIDE("/", 6), REMAINDER("%", 6);

    final String symbol;
    final int precedence;

    Operator(String symbol, int precedence) {
      this.symbol = symbol;
      this.precedence = precedence;
    }

    @Override
    public String toString() {
      return symbol;
    }
  }

  /**
   * Maps a code point to the operators that begin with that code point. For example, maps
   * {@code <} to {@code LESS} and {@code LESS_OR_EQUAL}.
   */
  private static final ImmutableListMultimap<Integer, Operator> CODE_POINT_TO_OPERATORS;
  static {
    ImmutableListMultimap.Builder<Integer, Operator> builder = ImmutableListMultimap.builder();
    for (Operator operator : Operator.values()) {
      if (operator != Operator.STOP) {
        builder.put((int) operator.symbol.charAt(0), operator);
      }
    }
    CODE_POINT_TO_OPERATORS = builder.build();
  }

  /**
   * Parses an expression, which can occur within a directive like {@code #if} or {@code #set},
   * or within a reference like {@code $x[$a + $b]} or {@code $x.m($a + $b)}.
   * <pre>{@code
   * <expression> -> <and-expression> |
   *                 <expression> || <and-expression>
   * <and-expression> -> <relational-expression> |
   *                     <and-expression> && <relational-expression>
   * <equality-exression> -> <relational-expression> |
   *                         <equality-expression> <equality-op> <relational-expression>
   * <equality-op> -> == | !=
   * <relational-expression> -> <additive-expression> |
   *                            <relational-expression> <relation> <additive-expression>
   * <relation> -> < | <= | > | >=
   * <additive-expression> -> <multiplicative-expression> |
   *                          <additive-expression> <add-op> <multiplicative-expression>
   * <add-op> -> + | -
   * <multiplicative-expression> -> <unary-expression> |
   *                                <multiplicative-expression> <mult-op> <unary-expression>
   * <mult-op> -> * | / | %
   * }</pre>
   */
  private ExpressionNode parseExpression() throws IOException {
    ExpressionNode lhs = parseUnaryExpression();
    return new OperatorParser().parse(lhs, 1);
  }

  /**
   * An operator-precedence parser for the binary operations we understand. It implements an
   * <a href="http://en.wikipedia.org/wiki/Operator-precedence_parser">algorithm</a> from Wikipedia
   * that uses recursion rather than having an explicit stack of operators and values.
   */
  private class OperatorParser {
    /**
     * The operator we have just scanned, in the same way that {@link #c} is the character we have
     * just read. If we were not able to scan an operator, this will be {@link Operator#STOP}.
     */
    private Operator currentOperator;

    OperatorParser() throws IOException {
      nextOperator();
    }

    /**
     * Parse a subexpression whose left-hand side is {@code lhs} and where we only consider
     * operators with precedence at least {@code minPrecedence}.
     *
     * @return the parsed subexpression
     */
    ExpressionNode parse(ExpressionNode lhs, int minPrecedence) throws IOException {
      while (currentOperator.precedence >= minPrecedence) {
        Operator operator = currentOperator;
        ExpressionNode rhs = parseUnaryExpression();
        nextOperator();
        while (currentOperator.precedence > operator.precedence) {
          rhs = parse(rhs, currentOperator.precedence);
        }
        lhs = new BinaryExpressionNode(lhs, operator, rhs);
      }
      return lhs;
    }

    /**
     * Updates {@link #currentOperator} to be an operator read from the input,
     * or {@link Operator#STOP} if there is none.
     */
    private void nextOperator() throws IOException {
      skipSpace();
      ImmutableList<Operator> possibleOperators = CODE_POINT_TO_OPERATORS.get(c);
      if (possibleOperators.isEmpty()) {
        currentOperator = Operator.STOP;
        return;
      }
      char firstChar = Chars.checkedCast(c);
      next();
      Operator operator = null;
      for (Operator possibleOperator : possibleOperators) {
        if (possibleOperator.symbol.length() == 1) {
          Verify.verify(operator == null);
          operator = possibleOperator;
        } else if (possibleOperator.symbol.charAt(1) == c) {
          next();
          operator = possibleOperator;
        }
      }
      if (operator == null) {
        throw parseException(
            "Expected " + Iterables.getOnlyElement(possibleOperators) + ", not just " + firstChar);
      }
      currentOperator = operator;
    }
  }

  /**
   * Parses an expression not containing any operators (except inside parentheses).
   * <pre>{@code
   * <unary-expression> -> <primary> |
   *                       ( <expression> ) |
   *                       ! <unary-expression>
   * }</pre>
   */
  private ExpressionNode parseUnaryExpression() throws IOException {
    skipSpace();
    ExpressionNode node;
    if (c == '(') {
      nextNonSpace();
      node = parseExpression();
      expect(')');
      skipSpace();
      return node;
    } else if (c == '!') {
      next();
      node = new NotExpressionNode(parseUnaryExpression());
      skipSpace();
      return node;
    } else {
      return parsePrimary();
    }
  }

  /**
   * Parses an expression containing only literals or references.
   * <pre>{@code
   * <primary> -> <reference> |
   *              <string-literal> |
   *              <integer-literal> |
   *              <boolean-literal>
   * }</pre>
   */
  private ExpressionNode parsePrimary() throws IOException {
    ExpressionNode node;
    if (c == '$') {
      next();
      node = parseRequiredReference();
    } else if (c == '"') {
      node = parseStringLiteral(c, true);
    } else if (c == '\'') {
      node = parseStringLiteral(c, false);
    } else if (c == '-') {
      // Velocity does not have a negation operator. If we see '-' it must be the start of a
      // negative integer literal.
      next();
      node = parseIntLiteral("-");
    } else if (isAsciiDigit(c)) {
      node = parseIntLiteral("");
    } else if (isAsciiLetter(c)) {
      node = parseBooleanLiteral();
    } else {
      throw parseException("Expected an expression");
    }
    skipSpace();
    return node;
  }

  /**
   * Parses a string literal, which may contain references to be expanded. Examples are
   * {@code "foo"} or {@code "foo${bar}baz"}.
   * <pre>{@code
   * <string-literal> -> <double-quote-literal> | <single-quote-literal>
   * <double-quote-literal> -> " <double-quote-string-contents> "
   * <double-quote-string-contents> -> <empty> |
   *                                   <reference> <double-quote-string-contents> |
   *                                   <character-other-than-"> <double-quote-string-contents>
   * <single-quote-literal> -> ' <single-quote-string-contents> '
   * <single-quote-string-contents> -> <empty> |
   *                                   <character-other-than-'> <single-quote-string-contents>
   * }</pre>
   */
  private ExpressionNode parseStringLiteral(int quote, boolean allowReferences)
      throws IOException {
    assert c == quote;
    next();
    ImmutableList.Builder<Node> nodes = ImmutableList.builder();
    StringBuilder sb = new StringBuilder();
    while (c != quote) {
      switch (c) {
        case '\n':
        case EOF:
          throw parseException("Unterminated string constant");
        case '\\':
          throw parseException(
              "Escapes in string constants are not currently supported");
        case '$':
          if (allowReferences) {
            if (sb.length() > 0) {
              nodes.add(new ConstantExpressionNode(resourceName, lineNumber(), sb.toString()));
              sb.setLength(0);
            }
            next();
            nodes.add(parseReference());
            break;
          }
          // fall through
        default:
          sb.appendCodePoint(c);
          next();
      }
    }
    next();
    if (sb.length() > 0) {
      nodes.add(new ConstantExpressionNode(resourceName, lineNumber(), sb.toString()));
    }
    return new StringLiteralNode(resourceName, lineNumber(), nodes.build());
  }

  private static class StringLiteralNode extends ExpressionNode {
    private final ImmutableList<Node> nodes;

    StringLiteralNode(String resourceName, int lineNumber, ImmutableList<Node> nodes) {
      super(resourceName, lineNumber);
      this.nodes = nodes;
    }

    @Override
    Object evaluate(EvaluationContext context) {
      StringBuilder sb = new StringBuilder();
      for (Node node : nodes) {
        node.render(context, sb);
      }
      return sb.toString();
    }
  }

  private ExpressionNode parseIntLiteral(String prefix) throws IOException {
    StringBuilder sb = new StringBuilder(prefix);
    while (isAsciiDigit(c)) {
      sb.appendCodePoint(c);
      next();
    }
    Integer value = Ints.tryParse(sb.toString());
    if (value == null) {
      throw parseException("Invalid integer: " + sb);
    }
    return new ConstantExpressionNode(resourceName, lineNumber(), value);
  }

  /**
   * Parses a boolean literal, either {@code true} or {@code false}.
   * <boolean-literal> -> true |
   *                      false
   */
  private ExpressionNode parseBooleanLiteral() throws IOException {
    String s = parseId("Identifier without $");
    boolean value;
    if (s.equals("true")) {
      value = true;
    } else if (s.equals("false")) {
      value = false;
    } else {
      throw parseException("Identifier in expression must be preceded by $ or be true or false");
    }
    return new ConstantExpressionNode(resourceName, lineNumber(), value);
  }

  private static final CharMatcher ASCII_LETTER =
      CharMatcher.inRange('A', 'Z')
          .or(CharMatcher.inRange('a', 'z'))
          .precomputed();

  private static final CharMatcher ASCII_DIGIT =
      CharMatcher.inRange('0', '9')
          .precomputed();

  private static final CharMatcher ID_CHAR =
      ASCII_LETTER
          .or(ASCII_DIGIT)
          .or(CharMatcher.anyOf("-_"))
          .precomputed();

  private static boolean isAsciiLetter(int c) {
    return (char) c == c && ASCII_LETTER.matches((char) c);
  }

  private static boolean isAsciiDigit(int c) {
    return (char) c == c && ASCII_DIGIT.matches((char) c);
  }

  private static boolean isIdChar(int c) {
    return (char) c == c && ID_CHAR.matches((char) c);
  }

  /**
   * Parse an identifier as specified by the
   * <a href="http://velocity.apache.org/engine/devel/vtl-reference-guide.html#Variables">VTL
   * </a>. Identifiers are ASCII: starts with a letter, then letters, digits, {@code -} and
   * {@code _}.
   */
  private String parseId(String what) throws IOException {
    if (!isAsciiLetter(c)) {
      throw parseException(what + " should start with an ASCII letter");
    }
    StringBuilder id = new StringBuilder();
    while (isIdChar(c)) {
      id.appendCodePoint(c);
      next();
    }
    return id.toString();
  }

  /**
   * Returns an exception to be thrown describing a parse error with the given message, and
   * including information about where it occurred.
   */
  private ParseException parseException(String message) throws IOException {
    StringBuilder context = new StringBuilder();
    if (c == EOF) {
      context.append("EOF");
    } else {
      int count = 0;
      while (c != EOF && count < 20) {
        context.appendCodePoint(c);
        next();
        count++;
      }
      if (c != EOF) {
        context.append("...");
      }
    }
    return new ParseException(message, resourceName, lineNumber(), context.toString());
  }
}
