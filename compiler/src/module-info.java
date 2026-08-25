module FearlessCompiler {
  requires org.antlr.antlr4.runtime;
  requires org.junit.jupiter.api;
  requires org.opentest4j;
  requires net.jqwik.api;
  requires java.compiler;
  requires java.net.http;
//  requires commons.cli;
  requires cmdline.app;
  requires java.logging;
  requires org.apache.commons.text;
  requires org.apache.commons.lang3;
  requires commons.cli;
  requires com.fasterxml.jackson.databind;
  requires com.fasterxml.jackson.core;
  opens main.java to com.fasterxml.jackson.databind;
  opens program.typesystem to net.jqwik.engine, org.junit.platform.commons;
}