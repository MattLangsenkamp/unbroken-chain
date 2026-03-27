package com.mattlangsenkamp.ubc

class MySuite extends munit.FunSuite {
  test("hello") {
    assertEquals(Hello.msg, "Hello World!")
  }
}

