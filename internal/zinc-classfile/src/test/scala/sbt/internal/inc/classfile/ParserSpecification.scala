/*
 * Zinc - The incremental compiler for Scala.
 * Copyright Scala Center, Lightbend, and Mark Harrah
 *
 * Licensed under Apache License 2.0
 * SPDX-License-Identifier: Apache-2.0
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package sbt
package internal
package inc
package classfile

import sbt.internal.util.ConsoleLogger

class ParserSpecification extends UnitSpec {

  val sampleClasses = List[Class[?]](
    this.getClass,
    classOf[java.lang.Integer],
    classOf[java.util.AbstractMap.SimpleEntry[String, String]],
    classOf[String],
    classOf[Thread],
    classOf[org.scalacheck.Properties],
    // exercises meta-annotation parsing
    classOf[java.lang.annotation.Retention]
    // I thought it would be nice to throw in a nested annotation example here,
    // but I couldn't find one that we could use without having to add another
    // JAR to the test classpath. it's fine, we have nested annotation testing
    // over in AnalyzeSpecification
  )

  for (c <- sampleClasses)
    "classfile.Parser" should s"not crash when parsing $c" in {
      val logger = ConsoleLogger()
      // logger.setLevel(sbt.util.Level.Debug)
      val classfile = Parser(sbt.io.IO.classfileLocation(c), logger)
      assert(classfile ne null)
      assert(classfile.types.nonEmpty)
    }

  it should "parse InnerClasses attribute for AbstractMap.SimpleEntry" in {
    val logger = ConsoleLogger()
    val c = classOf[java.util.AbstractMap.SimpleEntry[String, String]]
    val cf = Parser(sbt.io.IO.classfileLocation(c), logger)
    val innerClasses = cf.innerClasses
    assert(innerClasses.nonEmpty)
    val self = innerClasses.find(_.innerClassName == "java.util.AbstractMap$SimpleEntry")
    assert(self.isDefined)
    assert(self.get.outerClassName == "java.util.AbstractMap")
  }

  // sbt/zinc#147: classes that appear only in a generic position survive erasure only in the
  // Signature attribute. signatureClassTypes recovers them; these cases pin down the parser's
  // handling of the JVMS 4.7.9 grammar (nested arguments, bounds, wildcards, inner classes, and the
  // type-variable exclusions a naive `L...;` scan would get wrong).
  it should "extract class references from generic signatures" in {
    def types(sig: String): Set[String] = Parser.signatureClassTypes(sig).toSet

    // a class used only as a type argument (the issue's `List<Foo>` example)
    assert(types("Ljava/util/List<Lp/Foo;>;") == Set("java.util.List", "p.Foo"))

    // method signature: type arguments in both parameter and return position
    assert(
      types("(Ljava/util/List<Lp/Foo;>;)Ljava/util/List<Lp/Bar;>;") ==
        Set("java.util.List", "p.Foo", "p.Bar")
    )

    // a type-parameter bound (`<T extends Foo>`); the formal parameter name `T` must not be treated
    // as a type variable and swallow the bound that follows
    assert(types("<T:Lp/Foo;>Ljava/lang/Object;") == Set("p.Foo", "java.lang.Object"))

    // an interface-only bound (empty class bound) still recovers the interface
    assert(
      types("<T::Ljava/lang/Comparable<TT;>;>Ljava/lang/Object;") ==
        Set("java.lang.Comparable", "java.lang.Object")
    )

    // nested type arguments
    assert(
      types("Ljava/util/Map<Ljava/lang/String;Ljava/util/List<Lp/Foo;>;>;") ==
        Set("java.util.Map", "java.lang.String", "java.util.List", "p.Foo")
    )

    // wildcards: extends / super / unbounded
    assert(types("Ljava/util/List<+Lp/Foo;>;") == Set("java.util.List", "p.Foo"))
    assert(types("Ljava/util/List<-Lp/Bar;>;") == Set("java.util.List", "p.Bar"))
    assert(types("Ljava/util/List<*>;") == Set("java.util.List"))

    // arrays of a parameterized type
    assert(types("[Ljava/util/List<Lp/Foo;>;") == Set("java.util.List", "p.Foo"))

    // an array used as a type argument (`List<Foo[]>`)
    assert(types("Ljava/util/List<[Lp/Foo;>;") == Set("java.util.List", "p.Foo"))

    // an array used as a type-parameter bound: not expressible in Java source (arrays are not legal
    // bounds), but valid in the grammar — the element class must still be recovered, not dropped
    assert(types("<T:[Lp/Foo;>Ljava/lang/Object;") == Set("p.Foo", "java.lang.Object"))

    // inner classes are reconstructed with their binary `$` name, alongside the enclosing class
    assert(types("Lp/Outer<Lp/Foo;>.Inner;") == Set("p.Outer", "p.Outer$Inner", "p.Foo"))

    // type variables are excluded — even one whose name happens to contain an `L`
    assert(types("TList;") == Set.empty)
    assert(types("TT;") == Set.empty)
  }

  it should "parse InnerClasses attribute for AbstractMap" in {
    val logger = ConsoleLogger()
    val c = classOf[java.util.AbstractMap[?, ?]]
    val cf = Parser(sbt.io.IO.classfileLocation(c), logger)
    val innerClasses = cf.innerClasses
    val entry = innerClasses.find(_.innerClassName == "java.util.AbstractMap$SimpleEntry")
    assert(entry.isDefined)
    assert(entry.get.outerClassName == "java.util.AbstractMap")
    assert(entry.get.isPublic)
  }

}
