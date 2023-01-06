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
import com.google.common.base.Joiner;
import com.google.common.base.Verify;
import com.google.common.collect.ContiguousSet;
import com.google.common.collect.ForwardingSortedSet;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.primitives.Chars;
import com.google.common.primitives.Ints;
import com.google.escapevelocity.DirectiveNode.BreakNode;
import com.google.escapevelocity.DirectiveNode.DefineNode;
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
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
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

  /**
   * Map from resource name to already-parsed template. This map is shared between all of the nested
   * {@link Parser} instances that result from {@code #parse} directives, so we will only ever read
   * and parse any given resource name once.
   */
  private final Map<String, Template> parseCache;

  /**
   * Macros that have been defined during this parse. This means macros defined in a given {@code
   * foo.vm} file, without regard to whatever macros might be defined in another {@code bar.vm}
   * file. If the same name is defined more than once in {@code foo.vm}, only the first definition
   * has any effect.
   */
  private final Map<String, Macro> macros = new TreeMap<>();

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

  Parser(
      Reader reader,
      String resourceName,
      Template.ResourceOpener resourceOpener,
      Map<String, Template> parseCache)
      throws IOException {
    this.reader = new LineNumberReader(reader);
    this.reader.setLineNumber(1);
    next();
    this.resourceName = resourceName;
    this.resourceOpener = resourceOpener;
    this.parseCache = parseCache;
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
      if (node instanceof SetNode && SetSpacing.shouldRemoveLastNodeBeforeSet(nodes)) {
        nodes.set(nodes.size() - 1, node);
      } else {
        nodes.add(node);
      }
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
        case '@':
          return parseMacroCallWithBody();
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
   * {@code $foo} or {@code $bar.baz} or {@code $foo.bar[$baz].buh()}; or it is text containing
   * neither references (no {@code $}) nor directives (no {@code #}).
   */
  private Node parseNonDirective() throws IOException {
    if (c == '$') {
      return parseDollar();
    } else {
      int firstChar = c;
      next();
      return parsePlainText(firstChar);
    }
  }

  private Node parseDollar() throws IOException {
    assert c == '$';
    next();
    boolean silent = c == '!';
    if (silent) {
      next();
    }
    if (isAsciiLetter(c) || c == '{') {
      return parseReference(silent);
    } else if (silent) {
      return parsePlainText("$!");
    } else {
      return parsePlainText('$');
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
      case "break":
        return parseBreak();
      case "set":
        node = parseSet();
        break;
      case "define":
        node = parseDefine();
        break;
      case "parse":
        node = parseParse();
        break;
      case "macro":
        return parseMacroDefinition();
      case "evaluate":
        return parseEvaluate();
      default:
        node = parseMacroCall("#", directive);
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
    skipSpace();
    if (c != '$') {
      throw parseException("Expected variable beginning with '$' for #foreach");
    }
    Node varNode = parseDollar();
    if (!(varNode instanceof PlainReferenceNode)) {
      throw parseException("Expected simple variable for #foreach");
    }
    String var = ((PlainReferenceNode) varNode).id;
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
   * Parses a {@code #break} token from the reader.
   *
   * <p>There is an optional scope, so you can write {@code #break ($foreach)},
   * {@code #break ($foreach.parent)}, {@code #break ($parse)}, and so on. We only support
   * {@code $foreach}. If there is no scope, we will break from the nearest {@code #foreach} or
   * {@code #parse}, or, if there is none, from the whole template.
   */
  private Node parseBreak() throws IOException {
    // Unlike every other directive, #break has an *optional* parenthesized parameter. But even if
    // we *don't* see a `(` after skipping spaces, we can safely discard the spaces. It's a #break,
    // so any plain text after it will never be rendered anyway. (We could even discard any
    // non-space plain text, but it's probably not worth bothering.) For the same reason, we don't
    // need to skip a \n that might occur after the #break.
    skipSpace();
    ExpressionNode scope = null;
    if (c == '(') {
      next();
      scope = parsePrimary();
      expect(')');
    }
    return new BreakNode(resourceName, lineNumber(), scope);
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
   * Parses a {@code #define} directive from the reader.
   *
   * <pre>{@code
   * #define ( $<id> ) <balanced-tokens> #end
   * }</pre>
   */
  private Node parseDefine() throws IOException {
    int startLine = lineNumber();
    expect('(');
    expect('$');
    String var = parseId("#define variable");
    expect(')');
    ParseResult parseResult =
        skipNewlineAndParseToStop(END_CLASS, () -> "parsing #define starting on line " + startLine);
    return new DefineNode(var, Node.cons(resourceName, startLine, parseResult.nodes));
  }

  /**
   * Parses a {@code #parse} token from the reader.
   *
   * <pre>{@code
   * #parse ( <primary> )
   * }</pre>
   *
   * <p>When we see a {@code #parse} directive while parsing a template, all we do is record it as a
   * {@link ParseNode} in the {@link Template} we produce. We only actually open and parse the
   * resource named in the {@code #parse} when the template is later <i>evaluated</i>. The {@code
   * parseCache} means that we will only do this once, at least if the argument to the {@code
   * #parse} is always the same string.
   */
  private Node parseParse() throws IOException {
    int startLine = lineNumber();
    expect('(');
    ExpressionNode nestedResourceNameExpression = parsePrimary();
    skipSpace();
    expect(')');
    return new ParseNode(
        resourceName, startLine, nestedResourceNameExpression, resourceOpener, parseCache);
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
   * {@code #directives} that Velocity supports but we currently don't, and that don't have to be
   * followed by {@code (}. If we see one of these, we should complain, rather than just ignoring it
   * the way we would for {@code #random} or whatever. If it <i>does</i> have to be followed by
   * {@code (} then we will treat it as an undefined macro, which is fine.
   */
  private static final ImmutableSet<String> UNSUPPORTED_VELOCITY_DIRECTIVES =
      ImmutableSet.of("stop");

  /**
   * Parses an identifier after {@code #} that is not one of the standard directives. The assumption
   * is that it is a call of a macro that is defined in the template. Macro definitions are
   * extracted from the template during parsing (and not during evaluation of the template as you
   * might expect). This means that a macro can be called before it is defined.
   *
   * <pre>{@code
   * #<id> ()
   * #<id> ( <expr1> )
   * #<id> ( <expr1> <expr2>)
   * #<id> ( <expr1> , <expr2>)
   * ...
   * }</pre>
   */
  private Node parseMacroCall(String prefix, String directive) throws IOException {
    int startLine = lineNumber();
    StringBuilder sb = new StringBuilder(prefix).append(directive);
    while (Character.isWhitespace(c)) {
      sb.appendCodePoint(c);
      next();
    }
    if (c != '(') {
      if (UNSUPPORTED_VELOCITY_DIRECTIVES.contains(directive)) {
        throw parseException("EscapeVelocity does not currently support #" + directive);
      }
      // Velocity allows #foo, where #foo is not a directive and is not followed by `(` (so it can't
      // be a macro call). Then it is just plain text. BUT, sometimes but not always, Velocity will
      // reject #endfoo, a string beginning with #end. So we do always reject that.
      if (directive.startsWith("end")) {
        throw parseException("Unrecognized directive #" + directive);
      }
      return parsePlainText(sb);
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
    Node bodyContent;
    if (prefix.equals("#")) {
      bodyContent = null;
    } else {
      ParseResult parseResult =
          skipNewlineAndParseToStop(
              END_CLASS, () -> "#@" + directive + " starting on line " + startLine);
      bodyContent = Node.cons(resourceName, startLine, parseResult.nodes);
    }
    return new DirectiveNode.MacroCallNode(
        resourceName, lineNumber(), directive, parameterNodes.build(), bodyContent);
  }

  private Node parseMacroCallWithBody() throws IOException {
    assert c == '@';
    next();
    if (!isAsciiLetter(c)) {
      return parsePlainText("#@");
    }
    String id = parseId("#@");
    return parseMacroCall("#@", id);
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

  /**
   * Parses plain text, which is text that contains neither {@code $} nor {@code #}. The given
   * {@code initialChars} are the first characters of the plain text, and {@link #c} is the
   * character after those.
   */
  private Node parsePlainText(String initialChars) throws IOException {
    return parsePlainText(new StringBuilder(initialChars));
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
   * <reference> -> $<maybe-silent><reference-no-brace> |
   *                $<maybe-silent>{<reference-no-brace>}
   * <maybe-silent> -> <empty> | !
   * }</pre>
   *
   * <p>On entry to this method, {@link #c} is the character immediately after the {@code $}, or
   * the {@code !} if there is one.
   *
   * @param silent true if this is {@code $!}.
   */
  private Node parseReference(boolean silent) throws IOException {
    if (c == '{') {
      next();
      if (!isAsciiLetter(c)) {
        if (silent) {
          return parsePlainText("$!{");
        } else {
          return parsePlainText("${");
        }
      }
      ReferenceNode node = parseReferenceNoBrace(silent);
      expect('}');
      return node;
    } else {
      return parseReferenceNoBrace(silent);
    }
  }

  /**
   * Same as {@link #parseReference}, except it really must be a reference. A {@code $} in
   * normal text doesn't start a reference if it is not followed by an identifier. But in an
   * expression, for example in {@code #if ($x == 23)}, {@code $} must be followed by an
   * identifier.
   *
   * <p>Velocity allows the {@code $!} syntax in these contexts, but it doesn't have any effect
   * since null values are allowed anyway.
   */
  private ReferenceNode parseRequiredReference() throws IOException {
    if (c == '!') {
      next();
    }
    if (c == '{') {
      next();
      ReferenceNode node = parseReferenceNoBrace(/* silent= */ false);
      expect('}');
      return node;
    } else {
      return parseReferenceNoBrace(/* silent= */ false);
    }
  }

  /**
   * Parses a reference, in the simple form without braces.
   * <pre>{@code
   * <reference-no-brace> -> <id><reference-suffix>
   * }</pre>
   */
  private ReferenceNode parseReferenceNoBrace(boolean silent) throws IOException {
    String id = parseId("Reference");
    ReferenceNode lhs = new PlainReferenceNode(resourceName, lineNumber(), id, silent);
    return parseReferenceSuffix(lhs, silent);
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
  private ReferenceNode parseReferenceSuffix(ReferenceNode lhs, boolean silent) throws IOException {
    switch (c) {
      case '.':
        return parseReferenceMember(lhs, silent);
      case '[':
        return parseReferenceIndex(lhs, silent);
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
  private ReferenceNode parseReferenceMember(ReferenceNode lhs, boolean silent) throws IOException {
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
      reference = parseReferenceMethodParams(lhs, id, silent);
    } else {
      reference = new MemberReferenceNode(lhs, id, silent);
    }
    return parseReferenceSuffix(reference, silent);
  }

  /**
   * Parses the parameters to a method reference, like {@code $foo.bar($a, $b)}.
   * <pre>{@code
   * <method-parameter-list> -> <empty> |
   *                            <non-empty-method-parameter-list>
   * <non-empty-method-parameter-list> -> <primary> |
   *                                      <primary> , <non-empty-method-parameter-list>
   * }</pre>
   *
   * @param lhs the reference node representing what appears to the left of the dot, like the
   *     {@code $x} in {@code $x.foo()}.
   */
  private ReferenceNode parseReferenceMethodParams(ReferenceNode lhs, String id, boolean silent)
      throws IOException {
    assert c == '(';
    nextNonSpace();
    ImmutableList.Builder<ExpressionNode> args = ImmutableList.builder();
    if (c != ')') {
      args.add(parsePrimary(/* nullAllowed= */ true));
      while (c == ',') {
        nextNonSpace();
        args.add(parsePrimary(/* nullAllowed= */ true));
      }
      if (c != ')') {
        throw parseException("Expected )");
      }
    }
    assert c == ')';
    next();
    return new MethodReferenceNode(lhs, id, args.build(), silent);
  }

  /**
   * Parses an index suffix to a reference, like {@code $x[$i]}.
   * <pre>{@code
   * <reference-index> -> [ <primary> ]
   * }</pre>
   *
   * @param lhs the reference node representing what appears to the left of the dot, like the
   *     {@code $x} in {@code $x[$i]}.
   */
  private ReferenceNode parseReferenceIndex(ReferenceNode lhs, boolean silent) throws IOException {
    assert c == '[';
    next();
    ExpressionNode index = parsePrimary();
    if (c != ']') {
      throw parseException("Expected ]");
    }
    next();
    ReferenceNode reference = new IndexReferenceNode(lhs, index, silent);
    return parseReferenceSuffix(reference, silent);
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

    /** True if this is an inequality operator, one of {@code < > <= >=}. */
    boolean isInequality() {
      // Slightly hacky way to check.
      return precedence == 4;
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
   * Parses an expression, which can occur within a directive like {@code #if} or {@code #set}.
   * Arbitrary expressions <i>can't</i> appear within a reference like {@code $x[$a + $b]} or
   * {@code $x.m($a + $b)}, consistent with Velocity.
   * <pre>{@code
   * <expression> -> <and-expression> |
   *                 <expression> || <and-expression>
   * <and-expression> -> <relational-expression> |
   *                     <and-expression> && <relational-expression>
   * <equality-expression> -> <relational-expression> |
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
      switch (c) {
        case 'a':
          wordOperator("and", Operator.AND);
          return;
        case 'o':
          wordOperator("or", Operator.OR);
          return;
        default: // this will fail later, but just stopping the expression here is fine
      }
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

    private void wordOperator(String symbol, Operator operator) throws IOException {
      String id = parseId("");
      if (id.equals(symbol)) {
        currentOperator = operator;
      } else {
        throw parseException("Expected '" + symbol + "' but was '" + id + "'");
      }
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
   *              <boolean-literal> |
   *              <list-literal>
   * }</pre>
   */
  private ExpressionNode parsePrimary() throws IOException {
    return parsePrimary(false);
  }

  private ExpressionNode parsePrimary(boolean nullAllowed) throws IOException {
    skipSpace();
    ExpressionNode node;
    if (c == '$') {
      next();
      node = parseRequiredReference();
    } else if (c == '"') {
      node = parseStringLiteral('"', true);
    } else if (c == '\'') {
      node = parseStringLiteral('\'', false);
    } else if (c == '-') {
      // Velocity does not have a negation operator. If we see '-' it must be the start of a
      // negative integer literal.
      next();
      node = parseIntLiteral("-");
    } else if (c == '[') {
      node = parseListLiteral();
    } else if (isAsciiDigit(c)) {
      node = parseIntLiteral("");
    } else if (isAsciiLetter(c)) {
      node = parseNotOrBooleanOrNullLiteral(nullAllowed);
    } else {
      throw parseException("Expected a reference or a literal");
    }
    skipSpace();
    return node;
  }

  /**
   * Parses a list or range literal.
   *
   * <pre>{@code
   * <list-literal> -> <empty-list> | <non-empty-list>
   * <empty-list> -> [ ]
   * <non-empty-list> -> [ <primary> <list-end>
   * <list-end> -> <range-end> | <remainder-of-list-literal>
   * <range-end> -> .. <primary> ]
   * <remainder-of-list-literal> -> <end-of-list> | , <primary> <remainder-of-list-literal>
   * <end-of-list> -> ]
   * }</pre>
   */
  private ExpressionNode parseListLiteral() throws IOException {
    assert c == '[';
    nextNonSpace();
    if (c == ']') {
      next();
      return new ListLiteralNode(resourceName, lineNumber(), ImmutableList.of());
    }
    ExpressionNode first = parsePrimary(false);
    if (c == '.') {
      return parseRangeLiteral(first);
    } else {
      return parseRemainderOfListLiteral(first);
    }
  }

  private ExpressionNode parseRangeLiteral(ExpressionNode first) throws IOException {
    assert c == '.';
    next();
    if (c != '.') {
      throw parseException("Expected two dots (..) not just one");
    }
    nextNonSpace();
    ExpressionNode last = parsePrimary(false);
    if (c != ']') {
      throw parseException("Expected ] at end of range literal");
    }
    nextNonSpace();
    return new RangeLiteralNode(resourceName, lineNumber(), first, last);
  }

  private ExpressionNode parseRemainderOfListLiteral(ExpressionNode first) throws IOException {
    ImmutableList.Builder<ExpressionNode> builder = ImmutableList.builder();
    builder.add(first);
    while (c == ',') {
      next();
      builder.add(parsePrimary(false));
    }
    if (c != ']') {
      throw parseException("Expected ] at end of list literal");
    }
    next();
    return new ListLiteralNode(resourceName, lineNumber(), builder.build());
  }

  private static class RangeLiteralNode extends ExpressionNode {
    private final ExpressionNode first;
    private final ExpressionNode last;

    RangeLiteralNode(
        String resourceName, int lineNumber, ExpressionNode first, ExpressionNode last) {
      super(resourceName, lineNumber);
      this.first = first;
      this.last = last;
    }

    @Override
    public String toString() {
      return "[" + first + ".." + last + "]";
    }

    @Override
    Object evaluate(EvaluationContext context, boolean undefinedIsFalse) {
      int from = first.intValue(context);
      int to = last.intValue(context);
      ImmutableSortedSet<Integer> set =
          (from <= to)
              ? ContiguousSet.closed(from, to)
              : ContiguousSet.closed(to, from).descendingSet();
      return new ForwardingSortedSet<Integer>() {
        @Override
        protected ImmutableSortedSet<Integer> delegate() {
          return set;
        }

        @Override
        public String toString() {
          // ContiguousSet returns [1..3] whereas Velocity uses [1, 2, 3].
          return set.asList().toString();
        }
      };
    }
  }

  private static class ListLiteralNode extends ExpressionNode {
    private final ImmutableList<ExpressionNode> elements;

    ListLiteralNode(String resourceName, int lineNumber, ImmutableList<ExpressionNode> elements) {
      super(resourceName, lineNumber);
      this.elements = elements;
    }

    @Override
    public String toString() {
      return "[" + Joiner.on(", ").join(elements) + "]";
    }

    @Override
    Object evaluate(EvaluationContext context, boolean undefinedIsFalse) {
      // We can't use ImmutableList because there can be nulls.
      List<Object> list = new ArrayList<>();
      for (ExpressionNode element : elements) {
        list.add(element.evaluate(context));
      }
      return Collections.unmodifiableList(list);
    }
  }

  /**
   * Parses a string literal, which may contain template text to be expanded. Examples are
   * {@code 'foo}, {@code "foo"}, and {@code "foo${bar}baz"}. Double-quote string literals
   * ({@code expand = true}) can have arbitrary template constructs inside them, such as references,
   * directives like {@code #if}, and macro calls. Single-quote literals really are literal.
   */
  private ExpressionNode parseStringLiteral(char quote, boolean expand) throws IOException {
    assert c == quote;
    next();
    StringBuilder sb = new StringBuilder();
    while (c != quote) {
      switch (c) {
        case EOF:
          throw parseException("Unterminated string constant");
        case '\\':
          throw parseException(
              "Escapes in string constants are not currently supported");
        default:
          sb.appendCodePoint(c);
          next();
      }
    }
    next();
    String s = sb.toString();
    ImmutableList<Node> nodes;
    if (expand) {
      // This is potentially something like "foo${bar}baz" or "foo#macro($bar)baz", where the text
      // inside "..." is expanded like a mini-template. Of course it might also just be a plain old
      // string like "foo", in which case we will just parse a single ConstantExpressionNode here.
      String where = "string " + ParseException.where(resourceName, lineNumber());
      Parser stringParser = new Parser(new StringReader(s), where, resourceOpener, parseCache);
      ParseResult parseResult = stringParser.parseToStop(EOF_CLASS, () -> "outside any construct");
      nodes = parseResult.nodes;
    } else {
      nodes = ImmutableList.of(new ConstantExpressionNode(resourceName, lineNumber(), s));
    }
    return new StringLiteralNode(resourceName, lineNumber(), quote, nodes);
  }

  private static class StringLiteralNode extends ExpressionNode {
    private final char quote;
    private final ImmutableList<Node> nodes;

    StringLiteralNode(String resourceName, int lineNumber, char quote, ImmutableList<Node> nodes) {
      super(resourceName, lineNumber);
      this.quote = quote;
      this.nodes = nodes;
    }

    @Override
    public String toString() {
      return quote + Joiner.on("").join(nodes) + quote;
    }

    @Override
    Object evaluate(EvaluationContext context, boolean undefinedIsFalse) {
      StringBuilder sb = new StringBuilder();
      for (Node node : nodes) {
        node.render(context, sb);
      }
      return sb.toString();
    }
  }

  /**
   * Parses an {@code #evaluate} token from the reader.
   *
   * <pre>{@code
   * #evaluate ( <primary> )
   * }</pre>
   */
  private Node parseEvaluate() throws IOException {
    int startLine = lineNumber();
    expect('(');
    ExpressionNode expression = parsePrimary();
    expect(')');
    if (c == '\n') {
      next();
    }
    return new EvaluateNode(resourceName, startLine, expression);
  }

  /**
   * An {@code #evaluate} directive. When we encounter {@code #evaluate (<foo>)}, we determine the
   * value of {@code <foo>}, which must be a string, then we parse that string as a template and
   * evaluate it.
   */
  private class EvaluateNode extends Node {
    private final ExpressionNode expression;

    EvaluateNode(String resourceName, int lineNumber, ExpressionNode expression) {
      super(resourceName, lineNumber);
      this.expression = expression;
    }

    @Override
    void render(EvaluationContext context, StringBuilder sb) {
      Object valueObject = expression.evaluate(context);
      if (valueObject == null) { // Velocity ignores an #evaluate with a null argument.
        return;
      }
      if (!(valueObject instanceof String)) {
        throw evaluationException("Argument to #evaluate must be a string: " + valueObject);
      }
      String value = (String) valueObject;
      String where = "#evaluate " + ParseException.where(resourceName, lineNumber());
      Template template;
      try {
        Parser parser = new Parser(new StringReader(value), where, resourceOpener, parseCache);
        template = parser.parse();
      } catch (IOException e) {
        throw evaluationException(e);
      }
      template.render(context, sb);
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
   * Parses a boolean literal, either {@code true} or {@code false}. Also allows {@code null}, but
   * only if {@code nullAllowed} is true. Velocity allows {@code null} as a method parameter but not
   * anywhere else.
   */
  private ExpressionNode parseNotOrBooleanOrNullLiteral(boolean nullAllowed) throws IOException {
    String id = parseId("Identifier without $");
    Object value;
    switch (id) {
      case "true":
        value = true;
        break;
      case "false":
        value = false;
        break;
      case "not":
        return new NotExpressionNode(parseUnaryExpression());
      case "null":
        if (nullAllowed) {
          value = null;
          break;
        }
        // fall through...
      default:
        String suffix = nullAllowed ? " or null" : "";
        throw parseException(
            "Identifier must be preceded by $ or be true or false" + suffix + ": " + id);
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
    int line = lineNumber();
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
    return new ParseException(message, resourceName, line, context.toString());
  }
}
