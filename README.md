# EscapeVelocity summary

EscapeVelocity is a templating engine that can be used from Java. It is a
reimplementation of a subset of functionality from
[Apache Velocity](http://velocity.apache.org/).

This is not an official Google product.

For a fuller explanation of Velocity's functioning, see its
[User Guide](https://velocity.apache.org/engine/2.4.1/user-guide.html)

If EscapeVelocity successfully produces a result from a template evaluation,
that result should be the exact same string that Velocity produces. If not, that
is a bug.

EscapeVelocity has no facilities for HTML escaping and it is not appropriate for
producing HTML output that might include portions of untrusted input.

## Motivation

Velocity has a convenient templating language. It is easy to read, and it has
widespread support from tools such as editors and coding websites. However,
*using* Velocity can prove difficult. Its use to generate Java code in the
[AutoValue][AutoValue] annotation processor required many
[workarounds][VelocityHacks]. The way it dynamically loads classes as part of
its standard operation makes it hard to
[shade](https://maven.apache.org/plugins/maven-shade-plugin/) it, which in the
case of AutoValue led to interference if Velocity was used elsewhere in a
project. Velocity also has a large and complex API, and has introduced several
incompatible changes over the years.

EscapeVelocity has a
[simple API](https://javadoc.io/doc/com.google.escapevelocity/escapevelocity/latest/index.html)
that does not involve any class-loading or other sources of problems. It and its
dependencies can be shaded with no difficulty. We take care to avoid
incompatible changes.

## Loading a template

The entry point for EscapeVelocity is the `Template` class. To obtain an
instance, use `Template.parseFrom(Reader)`. If a template is stored in a file,
that file conventionally has the suffix `.vm` (for Velocity Macros). But since
the argument is a `Reader`, you can also load a template directly from a Java
string, using `StringReader`.

Here's how you might make a `Template` instance from a template file that is
packaged as a resource in the same package as the calling class:

```java
InputStream in = getClass().getResourceAsStream("foo.vm");
if (in == null) {
  throw new IllegalArgumentException("Could not find resource foo.vm");
}
Template template = Template.parseFrom(new InputStreamReader(in));
```

## Expanding a template

Once you have a `Template` object, you can use it to produce a string where the
variables in the template are given the values you provide. You can do this any
number of times, specifying the same or different values each time.

Suppose you have this template:

```
The $language word for $original is $translated.
```

You might write this code:

```java
Map<String, String> vars = Map.of(
    "language", "French",
    "original", "toe",
    "translated", "orteil");
String result = template.evaluate(vars);
```

The `result` string would then be: `The French word for toe is orteil.`

## Comments

The characters `##` introduce a comment. Characters from `##` up to and
including the following newline are omitted from the template. This template has
comments:

```
Line 1 ## with a comment
Line 2
```

It is the same as this template: `Line 1 Line 2`

## References

EscapeVelocity supports most of the reference types described in the
[Velocity User Guide](https://velocity.apache.org/engine/2.4.1/user-guide.html#references)

### Variables

A variable has an ASCII name that starts with a letter (a-z or A-Z) and where
any other characters are also letters or digits or hyphens (-) or underscores
(`_`). A variable reference can be written as `$foo` or as `${foo}`. The value
of a variable can be of any Java type. If the value `v` of variable `foo` is not
a String then the result of `$foo` in a template will be `String.valueOf(v)`.
Variables must be defined before they are referenced; otherwise an
`EvaluationException` will be thrown.

Variable names are case-sensitive: `$foo` is not the same variable as `$Foo` or
`$FOO`.

Initially the values of variables come from the Map that is passed to
`Template.evaluate`. Those values can be changed, and new ones defined, using
the `#set` directive in the template:

```
#set ($foo = "bar")
```

Setting a variable affects later references to it in the template, but has no
effect on the `Map` that was passed in or on later template evaluations.

### Properties

If a reference looks like `$purchase.Total` then the value of the `$purchase`
variable must be a Java object that has a public method `getTotal()` or
`gettotal()`, or a method called `isTotal()` or `istotal()` that returns
`boolean`. The result of `$purchase.Total` is then the result of calling that
method on the `$purchase` object.

If you want to have a period (`.`) after a variable reference *without* it being
a property reference, you can use braces like this: `${purchase}.Total`. If,
after a property reference, you have a further period, you can put braces around
the reference like this: `${purchase.Total}.nonProperty`.

As a special case, if `$purchase` is a Java `Map`, `$purchase.Total` is the
result of calling `get("Total")` on the `Map`.

### Methods

If a reference looks like `$purchase.addItem("scones", 23)` then the value of
the `$purchase` variable must be a Java object that has a public method
`addItem` with two parameters that match the given values. Unlike Velocity,
EscapeVelocity requires that there be exactly one such method. It is OK if there
are other `addItem` methods provided they are not compatible with the arguments
provided.

Properties are in fact a special case of methods: instead of writing
`$purchase.Total` you could write `$purchase.getTotal()`. Braces can be used to
make the method invocation explicit (`${purchase.getTotal()}`) or to prevent
method invocation (`${purchase}.getTotal()`).

If the object that the method is being called on is an instance of
`java.lang.Class`, then the method can be one of the methods of
`java.lang.Class`, *or* it can be a static method in the class in question. For
example if `$Objects` is `java.util.Objects.class`, then `$Objects.equals($a,
$b)` will invoke the static method
[`java.util.Objects.equals`](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/util/Objects.html#equals\(java.lang.Object,java.lang.Object\))
with the given parameters.

A method parameter can be `null` to indicate a null value. For example
`$Objects.equals(null, null)` would evaluate to `true`, given the above
definition of `$Objects`.

### Indexing

If a reference looks like `$indexme[$i]` then the value of the `$indexme`
variable must be a Java object that has a public `get` method that takes one
argument that is compatible with the index. For example, `$indexme` might be a
`List` and `$i` might be an integer. Then the reference would be the result of
`List.get(int)` for that list and that integer. Or, `$indexme` might be a `Map`,
and the reference would be the result of `Map.get(Object)` for the object `$i`.
In general, `$indexme[$i]` is equivalent to `$indexme.get($i)`.

For lists specifically, the index can be negative, and then it counts from the
end of the list. For example `$list[-1]` is the last element of `$list`.

Unlike Velocity, EscapeVelocity does not allow `$indexme` to be a Java array.

### Undefined references

If a variable has not been given a value, either by being in the initial Map
argument or by being set in the template, then referencing it will provoke an
`EvaluationException`. There is a special case for `#if`: if you write `#if
($var)` then it is allowed for `$var` not to be defined, and it is treated as
false.

### Null references

A reference can produce a null value, for example `$foo` if the input `Map` has
an entry for `"foo"` with a null value, or `$indexme[$i]` if `$indexme` is a
`List` that has a null element at index `$i`. If you try to insert a null
reference into the output of a template then you will get an exception. If you
use `$!` instead of `$`, like `$!foo` or `$!indexme[$i]`, then a null reference
will instead produce nothing in the output.

### Setting properties and indexes: not supported

Unlke Velocity, EscapeVelocity does not allow `#set` assignments with properties
or indexes:

```
#set ($data.User = "jon")        ## Allowed in Velocity but not in EscapeVelocity
#set ($map["apple"] = "orange")  ## Allowed in Velocity but not in EscapeVelocity
```

## Expressions

In certain contexts, such as the `#set` directive we have just seen or certain
other directives, EscapeVelocity can evaluate expressions. An expression can be
any of these:

*   A reference, of the kind we have just seen. The value is the value of the
    reference.
*   A string literal, as described below.
*   An integer literal such as `23` or `-100`. EscapeVelocity does not support
    floating-point literals.
*   A Boolean literal, `true` or `false`.
*   A list literal, as described below.
*   A map literal, like `{'key1': $value1, $key2: 'value2'}`. The value is a
    mutable Java map with the given keys and values.
*   Simpler expressions joined together with operators that have the same
    meaning as in Java: `!`, `==`, `!=`, `<`, `<=`, `>`, `>=`, `&&`, `||`, `+`,
    `-`, `*`, `/`, `%`. The operators have the same precedence as in Java.
*   A simpler expression in parentheses, for example `(2 + 3)`.

### String literals

There are two forms of string literals that can appear in expressions. The
simpler form is surrounded with single quotes (`'...'`) and represents a string
containing everything between those quotes. The other form is surrounded with
double quotes (`"..."`) and again represents a string containing everything
between the quotes, but this time the text can contain references like
`$purchase.Total` and directives like `#if ($condition) yes #end`.

String literals can span more than one line.

### List literals

There are two forms of list literals that can appear in expressions. An explicit
list such as `[]`, `[23]`, or `["a", "b"]` evaluates to a Java `List` containing
those values. A range such as `[0..$i]` or `[$from .. $to]` evaluates to a Java
`List` containing the integer values from the first number to the second number,
inclusive. If the second number is less than the first, the list values
decrease.

## Directives

A directive is introduced by a `#` character followed by a word. We have already
seen the `#set` directive, which sets the value of a variable. The other
directives are listed below.

Directives can be spelled with or without braces, so `#set` or `#{set}`.

### `#if`/`#elseif`/`#else`

The `#if` directive selects parts of the template according as a condition is
true or false. The simplest case looks like this:

```
#if ($condition) yes #end
```

This evaluates to the string `yes` if the variable `$condition` is defined and
has a true value, and to the empty string otherwise. It is allowed for
`$condition` not to be defined in this case, and then it is treated as false.

The expression in `#if` (here `$condition`) is considered true if its value is
not null and not equal to the Boolean value `false`.

An `#if` directive can also have an `#else` part, for example:

```
#if ($condition) yes #else no #end
```

This evaluates to the string `yes` if the condition is true or the string `no`
if it is not.

An `#if` directive can have any number of `#elseif` parts. For example:

```
#if ($i == 0) zero #elseif ($i == 1) one #elseif ($i == 2) two #else many #end
```

### `#foreach`

The `#foreach` directive repeats a part of the template once for each value in a
list.

```
#foreach ($product in $allProducts)
  ${product}!
#end
```

This will produce one line for each value in the `$allProducts` variable. The
value of `$allProducts` can be a Java `Iterable`, such as a `List` or `Set`; or
it can be an object array; or it can be a Java `Map`. When it is a `Map` the
`#foreach` directive loops over every *value* in the `Map`.

If `$allProducts` is a `List` containing the strings `oranges` and `lemons` then
the result of the `#foreach` would be this:

```

  oranges!


  lemons!

```

When the `#foreach` completes, the loop variable (`$product` in the example)
goes back to whatever value it had before, or to being undefined if it was
undefined before.

Within the `#foreach`, the special variable `$foreach` is defined.

`$foreach.hasNext` will be true if there are more values after this one or false
if this is the last value. `$foreach.index` will be the index of the iteration,
starting at 0. For example:

```
#foreach ($product in $allProducts)${foreach.index}: ${product}#if ($foreach.hasNext), #end#end
```

This would produce the output `0: oranges, 1: lemons` for the list above. (The
example is scrunched up to avoid introducing extraneous spaces, as described in
the [section](#spaces) on spaces below.)

`$foreach.first` and `$foreach.last` are true for the first and last iteration,
respectively, and false for other iterations. So `$foreach.last` is the negation
of `$foreach.hasNext`.

`$foreach.count` is one more than `$foreach.index`.

The `#foreach` directive is often used with list literals:

```
#foreach ($i in [1..$n])
  #foreach ($j in ["a", "b", "c"])
    $someObject.someMethod($i, $j)
  #end
#end
```

### Macros

A macro is a part of the template that can be reused in more than one place,
potentially with different parameters each time. In the simplest case, a macro
has no arguments:

```
#macro (hello) bonjour #end
```

Then the macro can be referenced by writing `#hello()` and the result will be
the string `bonjour` inserted at that point.

Macros can also have parameters:

```
#macro (greet $hello $world) $hello, $world! #end
```

Then `#greet("bonjour", "monde")` would produce `bonjour, monde!`. The comma is
optional, so you could also write `#greet("bonjour" "monde")`.

When a macro completes, the parameters (`$hello` and `$world` in the example) go
back to whatever values they had before, or to being undefined if they were
undefined before.

All macro definitions take effect before the template is evaluated, so you can
use a macro at a point in the template that is before the point where it is
defined. This also means that you can't define a macro conditionally:

```
## This doesn't work!
#if ($language == "French")
#macro (hello) bonjour #end
#else
#macro (hello) hello #end
#end
```

There is no particular reason to define the same macro more than once, but if
you do it is the first definition that is retained. In the `#if` example just
above, the `bonjour` version will always be used.

Macros can make templates hard to understand. You may prefer to put the logic in
a Java method rather than a macro, and call the method from the template using
`$methods.doSomething("foo")` or whatever.

## Block quoting

If you have text that should be treated verbatim, you can enclose it in
`#[[...]]#`. The text represented by `...` will be copied into the output. `#`
and `$` characters will have no effect in that text.

```
#[[ This is not a #directive, and this is not a $variable. ]]#
```

## Including other templates

If you want to include a template from another file, you can use the `#parse`
directive. This can be useful if you have macros that are shared between
templates, for example.

```
#set ($foo = "bar")
#parse("macros.vm")
#mymacro($foo) ## #mymacro defined in macros.vm
```

For this to work, you will need to tell EscapeVelocity how to find "resources"
such as `macro.vm` in the example. You might use something like this:

```
ResourceOpener resourceOpener = resourceName -> {
  InputStream inputStream = getClass().getResource(resourceName).openStream();
  if (inputStream == null) {
    throw new IOException("Unknown resource: " + resourceName);
  }
  return new InputStreamReader(inputStream, StandardCharsets.UTF_8);
};
Template template = Template.parseFrom("foo.vm", resourceOpener);
```

In this case, the `resourceOpener` is used to find the main template `foo.vm`,
as well as any templates it may reference in `#parse` directives.

A `#parse` directive only reads and parses the named template (`macros.vm` in
the example) when the containing template (`foo.vm`) is evaluated
(`template.evaluate(vars)`). The result is cached, so if you do
`template.evaluate(vars)` a second time it will use the already-parsed
`macros.vm` from the first time.

## <a name="spaces"></a> Spaces

For the most part, spaces and newlines in the template are preserved exactly in
the output. To avoid unwanted newlines, you may end up using `##` comments. In
the `#foreach` example above we had this:

```
#foreach ($product in $allProducts)${product}#if ($foreach.hasNext), #end#end
```

That was to avoid introducing unwanted spaces and newlines. A more readable way
to achieve the same result is this:

```
#foreach ($product in $allProducts)##
${product}##
#if ($foreach.hasNext), #end##
#end
```

Spaces are ignored between the `#` of a directive and the `)` that closes it, so
there is no trace in the output of the spaces in `#foreach ($product in
$allProducts)` or `#if ($foreach.hasNext)`. Spaces are also ignored inside
references, such as `$indexme[ $i ]` or `$callme( $i , $j )`.

If you are concerned about the detailed formatting of the text from the
template, you may want to post-process it. For example, if it is Java code, you
could use a formatter such as
[google-java-format](https://github.com/google/google-java-format). Then you
shouldn't have to worry about extraneous spaces.

[VelocityHacks]: https://github.com/google/auto/blob/ca2384d5ad15a0c761b940384083cf5c50c6e839/value/src/main/java/com/google/auto/value/processor/TemplateVars.java#L54
[AutoValue]: https://github.com/google/auto/tree/main/value
