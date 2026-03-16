package codegen.js;

import main.CompilerFrontEnd;
import main.InputOutput;
import main.Main;
import main.js.LogicMainJs;
import org.junit.jupiter.api.Test;
import utils.Base;
import utils.Err;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;

import static codegen.js.RunJsProgramTests.ok;

public class TestDomAccess {
  JsProgram getCode(String... content) {
    Main.resetAll();
    var vb = new CompilerFrontEnd.Verbosity(false, false, CompilerFrontEnd.ProgressVerbosity.None);
    var main = LogicMainJs.of(InputOutput.programmaticAuto(Arrays.asList(content)), vb);
    var fullProgram = main.parse(); // builds the AST from source files (base + test)
    main.wellFormednessFull(fullProgram); // checks semantic correctness of the high-level AST
    var program = main.inference(fullProgram); // runs type inference, producing a typed AST
    main.wellFormednessCore(program); // validates the core typed program
    var resolvedCalls = main.typeSystem(program); // resolves method calls, producing a mapping of call sites
    var mir = main.lower(program, resolvedCalls); // AST → MIR
    var code = main.codeGeneration(mir);
    return code;
  }
  void ok(String expected, String fileName, String... content) {
    assert content.length > 0 : "Content must not be empty";
    JsProgram code = getCode(content);
    var fileCode = code.files().stream()
      .filter(f -> f.toUri().toString().endsWith(fileName))
      .map(JsFile::code)
      .findFirst().orElseThrow();
    Err.strCmp(expected, fileCode);
  }
  void okList(List<String> expected, List<String> fileName, String... content) {
    assert content.length > 0 : "Content must not be empty";
    assert expected.size() == fileName.size() : "Expected and fileName lists must have the same size";
    JsProgram code = getCode(content);
    for (int i = 0; i < expected.size(); i++) {
      String exp = expected.get(i);
      String fName = fileName.get(i);
      var fileCode = code.files().stream()
        .filter(f -> f.toUri().toString().endsWith(fName))
        .map(JsFile::code)
        .findFirst().orElseThrow();
      Err.strCmp(exp, fileCode);
    }
  }
  enum BuildMode { DEBUG, PRODUCTION }

  void bundleWithRollup(Path tmpOut, Path mainEntry, Path outDir) throws IOException, InterruptedException {
    // 1) Rollup → bundle.js
    ProcessBuilder pb = new ProcessBuilder(
      "npx", "rollup",
      "--input", mainEntry.toString(),
      "--file", tmpOut.resolve("bundle.js").toString(),
      "--format", "esm",
      "--external", "fs",
      "--external", "path",
      "--external", "url"
    );
    pb.directory(tmpOut.toFile());
//    pb.inheritIO();
    pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
    pb.redirectError(ProcessBuilder.Redirect.DISCARD);
    if (pb.start().waitFor() != 0)
      throw new RuntimeException("Rollup bundling failed");

    // 2) Terser → bundle.min.js
    ProcessBuilder minify = new ProcessBuilder(
      "npx", "terser",
      tmpOut.resolve("bundle.js").toString(),
      "--compress", "--mangle",
      "--module",
      "--ecma", "2022",
      "--output", outDir.resolve("bundle.min.js").toString()
    );
    minify.directory(tmpOut.toFile());
    minify.inheritIO();
//    minify.redirectOutput(ProcessBuilder.Redirect.DISCARD);
//    minify.redirectError(ProcessBuilder.Redirect.DISCARD);
    if (minify.start().waitFor() != 0)
      throw new RuntimeException("Terser minification failed");

    // Copy wasm only
    Path wasmSrc = tmpOut.resolve("rt-js/libwasm/native_rt_bg.wasm");
    Path wasmDst = outDir.resolve("rt-js/libwasm/native_rt_bg.wasm");
    Files.createDirectories(wasmDst.getParent());
    Files.copy(wasmSrc, wasmDst, StandardCopyOption.REPLACE_EXISTING);
  }

  Path createIndexHtml(String html, BuildMode mode, Path outDir) throws IOException {
    String scriptTag = (mode == BuildMode.PRODUCTION)
      ? "<script type=\"module\" src=\"./bundle.min.js\"></script>\n"
      : "<script type=\"module\" src=\"./main.js\"></script>\n";

    int bodyIndex = html.lastIndexOf("</body>");
    String htmlWithScript = (bodyIndex == -1)
      ? html + scriptTag
      : html.substring(0, bodyIndex) + scriptTag + html.substring(bodyIndex);

    Path htmlPath = outDir.resolve("index.html");
    Files.writeString(htmlPath, htmlWithScript);
    return htmlPath;
  }

  Path generateMainJsEntry(Path outDir, String mainJsfileName) throws IOException {
    String mainJsModuleName = mainJsfileName
      .replace("/", "$$")
      .replace(".js", "");  // e.g., "todolist/App_0.js" → "todolist$$App_0"

    String mainJsContent = """
        import { rt$$NativeRuntime } from './rt-js/NativeRuntime.js';
        import {RealSystem} from './rt-js/RealSystem.js';
        import { %s } from './%s';
        rt$$NativeRuntime.ensureWasm();
        %s.$self.$hash$imm$1(new RealSystem());
        """.formatted(mainJsModuleName, mainJsfileName, mainJsModuleName);

    Path mainEntry = outDir.resolve("main.js");
    Files.writeString(mainEntry, mainJsContent);
    return mainEntry;
  }

  void createApp(BuildMode mode, String htmlContent, String mainJsfileName, String... content)
    throws IOException, InterruptedException {
    assert content.length > 0 : "Content must not be empty";
    // 1) Compile Fearless → JS
    JsProgram code = getCode(content);
    // 2) Temporary compilation directory
    Path tmpOut = Files.createTempDirectory("fgenjs");
    code.writeJsFiles(tmpOut);
    // 3) Determine final output directory
    Path outDir = (mode == BuildMode.PRODUCTION)
      ? tmpOut.resolve("production")
      : tmpOut;
    Files.createDirectories(outDir);
    // 4) Generate main.js entry
    Path mainEntry = generateMainJsEntry(tmpOut, mainJsfileName);
    // 5) Bundle & minify in production
    if (mode == BuildMode.PRODUCTION) {
      bundleWithRollup(tmpOut, mainEntry, outDir);
    }
    // 6) Write index.html into outDir
    createIndexHtml(htmlContent, mode, outDir);
    System.out.println("✅ App ready at: " + outDir.toUri());
  }


  @Test void dom() {ok("""
    import { base$$Void_0 } from "../base/Void_0.js";
    import { rt$$Documents } from "../rt-js/Documents.js";
    import { rt$$Str } from "../rt-js/Str.js";
    
    export class test$$Test_0 {
      static $hash$imm$2$fun(s_m$, $this) {
        let dom_m$ = rt$$Documents.$self.$hash$imm$1(s_m$);
    let input_m$ = dom_m$.getElementById$mut$1(rt$$Str.fromJsStr("todoInput")).$exclamation$mut$0();
    let addBtn_m$ = dom_m$.getElementById$mut$1(rt$$Str.fromJsStr("addBtn")).$exclamation$mut$0();
    let list_m$ = dom_m$.getElementById$mut$1(rt$$Str.fromJsStr("todoList")).$exclamation$mut$0();
    return base$$Void_0.$self;
      }
    }
    
    export class test$$Test_0Impl {
      $hash$imm$1(s_m$) { return test$$Test_0.$hash$imm$2$fun(s_m$, this); }
    }
    
    test$$Test_0.$self = new test$$Test_0Impl();
    """,
    "test/Test_0.js",
    """
    package test
    alias base.Documents as Documents, alias base.Document as Document, alias base.Element as Element, alias base.Main as Main, alias base.Block as Block, alias base.Void as Void,
    Test: base.Main{ s -> Block#
      .let[mut Document] dom = {Documents#s}
      .let[mut Element] input = { dom.getElementById("todoInput")! }
      .let[mut Element] addBtn = { dom.getElementById("addBtn")! }
      .let[mut Element] list = { dom.getElementById("todoList")! }
      .return{{}}
    }
    """);
  }
  @Test void minimalApp() {ok("""
    import { base$$Void_0 } from "../base/Void_0.js";
    import { rt$$Documents } from "../rt-js/Documents.js";
    import { rt$$Str } from "../rt-js/Str.js";
    import { test$$Fear720$_0 } from "../test/Fear720$_0.js";
    
    export class test$$App_0 {
      static $hash$imm$2$fun(s_m$, $this) {
        let dom_m$ = rt$$Documents.$self.$hash$imm$1(s_m$);
    let myBtn_m$ = dom_m$.getElementById$mut$1(rt$$Str.fromJsStr("myBtn")).$exclamation$mut$0();
    var doRes1 = myBtn_m$.onClick$mut$1(test$$Fear720$_0.$self);
    var doRes2 = s_m$.io$mut$0().print$mut$1(rt$$Str.fromJsStr("Hello"));
    return base$$Void_0.$self;
      }
    }
    
    export class test$$App_0Impl {
      $hash$imm$1(s_m$) { return test$$App_0.$hash$imm$2$fun(s_m$, this); }
    }
    
    test$$App_0.$self = new test$$App_0Impl();
    """,
    "test/App_0.js",
    """
    package test
    alias base.Documents as Documents, alias base.Document as Document, alias base.Element as Element,
    alias base.Block as Block, alias base.Void as Void, alias base.Main as Main,
    App: base.Main{ s -> Block#
      .let[mut Document] dom = {Documents#s}
      .let[mut Element] myBtn = { dom.getElementById("myBtn")! }
      .do{
         myBtn.onClick({ev, d -> ev.target.setClass("clicked")})
      }
      .do{ s.io.print("Hello") }
      .return{ Void }
    }
    """);
  }
  @Test void addTask() {
    ok("""
    import { base$$True_0 } from "../base/True_0.js";
    import { base$$Void_0 } from "../base/Void_0.js";
    import { rt$$Str } from "../rt-js/Str.js";
    import { todo$$Fear20$_0 } from "../todo/Fear20$_0.js";
    import { todo$$Fear30$_0 } from "../todo/Fear30$_0.js";
    
    export class todo$$AddTask_0 {
      static $hash$read$3$fun(e_m$, dom_m$, $this) {
        let input_m$ = dom_m$.getElementById$mut$1(rt$$Str.fromJsStr("todoInput")).$exclamation$mut$0();
    let list_m$ = dom_m$.getElementById$mut$1(rt$$Str.fromJsStr("todoList")).$exclamation$mut$0();
    let text_m$ = input_m$.value$read$0();
    if (text_m$.$equals$equals$imm$1(rt$$Str.fromJsStr("")) == base$$True_0.$self) { return base$$Void_0.$self; }
    let li_m$ = dom_m$.createElement$mut$1(rt$$Str.fromJsStr("li"));
    let span_m$ = dom_m$.createElement$mut$1(rt$$Str.fromJsStr("span"));
    var doRes1 = span_m$.setText$mut$1(text_m$);
    var doRes2 = span_m$.onClick$mut$1(todo$$Fear20$_0.$self);
    let delBtn_m$ = dom_m$.createElement$mut$1(rt$$Str.fromJsStr("button"));
    var doRes3 = delBtn_m$.setText$mut$1(rt$$Str.fromJsStr("x"));
    var doRes4 = delBtn_m$.onClickWith$mut$2(li_m$,todo$$Fear30$_0.$self);
    var doRes5 = li_m$.appendChild$mut$1(span_m$);
    var doRes6 = li_m$.appendChild$mut$1(delBtn_m$);
    var doRes7 = list_m$.appendChild$mut$1(li_m$);
    var doRes8 = input_m$.setValue$mut$1(rt$$Str.fromJsStr(""));
    var doRes9 = input_m$.focus$mut$0();
    return base$$Void_0.$self;
      }
    }
    
    export class todo$$AddTask_0Impl {
      $hash$read$2(e_m$, dom_m$) { return todo$$AddTask_0.$hash$read$3$fun(e_m$, dom_m$, this); }
    }
    
    todo$$AddTask_0.$self = new todo$$AddTask_0Impl();
    """,
    "todo/AddTask_0.js",
    """
    package todo
    alias base.Document as Document, alias base.Documents as Documents, alias base.Element as Element, alias base.Main as Main, alias base.Block as Block, alias base.Void as Void, alias base.F as F, alias base.Event as Event, alias base.Str as Str,
    AddTask: F[Event, mut Document, Void]{ e, dom -> Block#
      // obtain input and list elements
      .let[mut Element] input = { dom.getElementById("todoInput")! }
      .let[mut Element] list  = { dom.getElementById("todoList")! }
      // read text
      .let[Str] text = { input.value }
      // if empty, do nothing line 9
      .if { text == "" }
      .return { Void }
       // create <li> and <span>
       .let[mut Element] li   = { dom.createElement("li") }
       .let[mut Element] span = { dom.createElement("span") }
       .do{ span.setText(text) }
  
       // toggle completion on span click line17
       .do{
         span.onClick({ev, d -> Block#
           .do{ ev.target.setClass("completed") }
           .return{ Void }
         })
       }
       // create delete button line 24
       .let[mut Element] delBtn = { dom.createElement("button") }
       .do{ delBtn.setText("x") }
       .do{
         delBtn.onClickWith(li, { ev, d, el -> Block#
           .do{ el.remove }
           .return{ Void }
         })
       }
  
       // attach line36
       .do{ li.appendChild(span) }
       .do{ li.appendChild(delBtn) }
       .do{ list.appendChild(li) }
  
       // reset input
       .do{ input.setValue("") }
       .do{ input.focus }
       .return{ Void }
    }
    """);
  }

  @Test void SimpleToDoList() {
    okList(List.of("""
    import { base$$True_0 } from "../base/True_0.js";
    import { base$$Void_0 } from "../base/Void_0.js";
    import { rt$$Str } from "../rt-js/Str.js";
    import { todolist$$Fear857$_0 } from "../todolist/Fear857$_0.js";
    import { todolist$$Fear867$_0 } from "../todolist/Fear867$_0.js";
    
    export class todolist$$AddTask_0 {
      static $hash$read$3$fun(e_m$, dom_m$, $this) {
        let input_m$ = dom_m$.getElementById$mut$1(rt$$Str.fromJsStr("todoInput")).$exclamation$mut$0();
    let list_m$ = dom_m$.getElementById$mut$1(rt$$Str.fromJsStr("todoList")).$exclamation$mut$0();
    let text_m$ = input_m$.value$read$0();
    if (text_m$.$equals$equals$imm$1(rt$$Str.fromJsStr("")) == base$$True_0.$self) { return base$$Void_0.$self; }
    let li_m$ = dom_m$.createElement$mut$1(rt$$Str.fromJsStr("li"));
    let span_m$ = dom_m$.createElement$mut$1(rt$$Str.fromJsStr("span"));
    var doRes1 = span_m$.setText$mut$1(text_m$);
    var doRes2 = span_m$.onClick$mut$1(todolist$$Fear857$_0.$self);
    let delBtn_m$ = dom_m$.createElement$mut$1(rt$$Str.fromJsStr("button"));
    var doRes3 = delBtn_m$.setText$mut$1(rt$$Str.fromJsStr("x"));
    var doRes4 = delBtn_m$.onClickWith$mut$2(li_m$,todolist$$Fear867$_0.$self);
    var doRes5 = li_m$.appendChild$mut$1(span_m$);
    var doRes6 = li_m$.appendChild$mut$1(delBtn_m$);
    var doRes7 = list_m$.appendChild$mut$1(li_m$);
    var doRes8 = input_m$.setValue$mut$1(rt$$Str.fromJsStr(""));
    var doRes9 = input_m$.focus$mut$0();
    return base$$Void_0.$self;
      }
    }
    
    export class todolist$$AddTask_0Impl {
      $hash$read$2(e_m$, dom_m$) { return todolist$$AddTask_0.$hash$read$3$fun(e_m$, dom_m$, this); }
    }
    
    todolist$$AddTask_0.$self = new todolist$$AddTask_0Impl();
    """),
    List.of("todolist/AddTask_0.js"),
    """
    package todolist
    alias base.Document as Document, alias base.Documents as Documents, alias base.Element as Element, alias base.Main as Main, alias base.Block as Block, alias base.Void as Void, alias base.F as F, alias base.Event as Event, alias base.Str as Str,
    AddTask: F[mut Event, mut Document, Void]{ e, dom -> Block#
      // obtain input and list elements
      .let[mut Element] input = { dom.getElementById("todoInput")! }
      .let[mut Element] list  = { dom.getElementById("todoList")! }
      // read text
      .let[Str] text = { input.value }
      // if empty, do nothing line 9
      .if { text == "" }
      .return { Void }
       // create <li> and <span>
       .let[mut Element] li   = { dom.createElement("li") }
       .let[mut Element] span = { dom.createElement("span") }
       .do{ span.setText(text) }
  
       // toggle completion on span click line17
       .do{
         span.onClick({ev, d -> Block#
           .do{ ev.target.setClass("completed") }
           .return{ Void }
         })
       }
       // create delete button line 24
       .let[mut Element] delBtn = { dom.createElement("button") }
       .do{ delBtn.setText("x") }
       .do{
         delBtn.onClickWith(li, { ev, d, el -> Block#
           .do{ el.remove }
           .return{ Void }
         })
       }
  
       // attach line34
       .do{ li.appendChild(span) }
       .do{ li.appendChild(delBtn) }
       .do{ list.appendChild(li) }
  
       // reset input
       .do{ input.setValue("") }
       .do{ input.focus }
       .return{ Void }
    }
    
    App: Main{ s -> Block#
       // --- create document and key elements --- line46
       .let[mut Document] dom = {Documents#s}
       .let[mut Element] addBtn = { dom.getElementById("addBtn")! }
       .let[mut Element] input  = { dom.getElementById("todoInput")! }

       // --- attach button click → AddTask ---
       .do{ addBtn.onClick(AddTask) } // line 52
       // --- attach Enter key → AddTask --- line 53
       .do{
         input.onKeyDown({ e, d -> Block#
           .if { e.key == "Enter" }
           .do{ AddTask#(e, d) }
           .return{ Void }
         })
       }
       .return{ Void }
     }
    """);
  }

  @Test void ToDoListFull() {
    okList(List.of("""
    import { base$$True_0 } from "../base/True_0.js";
    import { base$$Void_0 } from "../base/Void_0.js";
    import { rt$$Str } from "../rt-js/Str.js";
    import { todolist$$Fear905$_0 } from "../todolist/Fear905$_0.js";
    import { todolist$$Fear924$_0 } from "../todolist/Fear924$_0.js";
    import { todolist$$Fear930$_0 } from "../todolist/Fear930$_0.js";
    
    export class todolist$$AddTask_0 {
      static $hash$read$3$fun(e_m$, dom_m$, $this) {
        let input_m$ = dom_m$.getElementById$mut$1(rt$$Str.fromJsStr("todoInput")).$exclamation$mut$0();
    let list_m$ = dom_m$.getElementById$mut$1(rt$$Str.fromJsStr("todoList")).$exclamation$mut$0();
    let text_m$ = input_m$.value$read$0();
    if (text_m$.$equals$equals$imm$1(rt$$Str.fromJsStr("")) == base$$True_0.$self) { return base$$Void_0.$self; }
    let li_m$ = dom_m$.createElement$mut$1(rt$$Str.fromJsStr("li"));
    var doRes1 = li_m$.setClass$mut$1(rt$$Str.fromJsStr("item"));
    var doRes2 = li_m$.setData$mut$2(rt$$Str.fromJsStr("done"),rt$$Str.fromJsStr("false"));
    let chk_m$ = dom_m$.createElement$mut$1(rt$$Str.fromJsStr("button"));
    var doRes3 = chk_m$.setClass$mut$1(rt$$Str.fromJsStr("chk"));
    var doRes4 = chk_m$.setText$mut$1(rt$$Str.fromJsStr(""));
    let span_m$ = dom_m$.createElement$mut$1(rt$$Str.fromJsStr("div"));
    var doRes5 = span_m$.setClass$mut$1(rt$$Str.fromJsStr("text"));
    var doRes6 = span_m$.setText$mut$1(text_m$);
    let delBtn_m$ = dom_m$.createElement$mut$1(rt$$Str.fromJsStr("button"));
    var doRes7 = delBtn_m$.setText$mut$1(rt$$Str.fromJsStr("x"));
    var doRes8 = delBtn_m$.setClass$mut$1(rt$$Str.fromJsStr("icon"));
    var doRes9 = span_m$.onClickWith$mut$2(li_m$,todolist$$Fear905$_0.$self);
    var doRes10 = chk_m$.onClickWith$mut$2(li_m$,todolist$$Fear924$_0.$self);
    var doRes11 = delBtn_m$.onClickWith$mut$2(li_m$,todolist$$Fear930$_0.$self);
    var doRes12 = li_m$.appendChild$mut$1(chk_m$);
    var doRes13 = li_m$.appendChild$mut$1(span_m$);
    var doRes14 = li_m$.appendChild$mut$1(delBtn_m$);
    var doRes15 = list_m$.appendChild$mut$1(li_m$);
    var doRes16 = input_m$.setValue$mut$1(rt$$Str.fromJsStr(""));
    var doRes17 = input_m$.focus$mut$0();
    return base$$Void_0.$self;
      }
    }
    
    export class todolist$$AddTask_0Impl {
      $hash$read$2(e_m$, dom_m$) { return todolist$$AddTask_0.$hash$read$3$fun(e_m$, dom_m$, this); }
    }
    
    todolist$$AddTask_0.$self = new todolist$$AddTask_0Impl();
    """),
    List.of("todolist/AddTask_0.js"),
    """
    package todolist
    alias base.Document as Document, alias base.Documents as Documents,
    alias base.Element as Element, alias base.Main as Main,
    alias base.Block as Block, alias base.Void as Void,
    alias base.F as F, alias base.Event as Event, alias base.Str as Str,
    alias base.Bool as Bool, alias base.True as True, alias base.False as False,

    ToggleDone: F[mut Event, mut Document, mut Element, Void]{ ev, d, el -> Block#
       .let[Str] done = { el.getData("done") }
       .do{ el.setClass(done == "true" ? {.then -> "item done", .else-> "item" }) }
       .do{ el.setData("done", done == "true" ? {.then -> "false", .else-> "true" }) }
       .return{ Void }
    }

    AddTask: F[mut Event, mut Document, Void]{ e, dom -> Block#
      // obtain input and list elements
      .let[mut Element] input = { dom.getElementById("todoInput")! }
      .let[mut Element] list  = { dom.getElementById("todoList")! }

      // read text line 13
      .let[Str] text = { input.value }

      // if empty, do nothing
      .if { text == "" } .return { Void }

      // create <li class="item">
      .let[mut Element] li = { dom.createElement("li") }
      .do{ li.setClass("item") }
      .do{ li.setData("done", "false") }

      // create checkbox button line 24
      .let[mut Element] chk = { dom.createElement("button") }
      .do{ chk.setClass("chk") }
      .do{ chk.setText("") }

      // create text div line 29
      .let[mut Element] span = { dom.createElement("div") }
      .do{ span.setClass("text") }
      .do{ span.setText(text) }

      // create delete button line 34
      .let[mut Element] delBtn = { dom.createElement("button") }
      .do{ delBtn.setText("x") }
      .do{ delBtn.setClass("icon") }

      // toggle completion when checkbox clicked line 39
      .do{
       span.onClickWith(li, {ev, d, el -> Block#
         .let[Str] done = { el.getData("done") }
         .do{ el.setClass(done == "true" ? {.then -> "item", .else-> "item done" }) }
         .do{ el.setData("done", done == "true" ? {.then -> "false", .else-> "true" }) }
         .return{ Void }
       })
      }
      .do{
       chk.onClickWith(li, {ev, d, el -> Block#
         .let[Str] done = { el.getData("done") }
         .do{ el.setClass(done == "true" ? {.then -> "item", .else-> "item done" }) }
         .do{ el.setData("done", done == "true" ? {.then -> "false", .else-> "true" }) }
         .return{ Void }
       })
      }

      // delete button logic
      .do{
        delBtn.onClickWith(li, { ev, d, el -> Block#
          .do{ el.remove }
          .return{ Void }
        })
      }

      // assemble
      .do{ li.appendChild(chk) }
      .do{ li.appendChild(span) }
      .do{ li.appendChild(delBtn) }
      .do{ list.appendChild(li) }

      // reset input
      .do{ input.setValue("") }
      .do{ input.focus }

      .return{ Void }
    }

    App: Main{ s -> Block#
      // create document and key elements
      .let[mut Document] dom = {Documents#s}
      .let[mut Element] addBtn = { dom.getElementById("addBtn")! }
      .let[mut Element] input  = { dom.getElementById("todoInput")! }

      // click → AddTask
      .do{ addBtn.onClick(AddTask) }

      // Enter key → AddTask
      .do{
        input.onKeyDown({ e, d -> Block#
          .if { e.key == "Enter" } .do{ AddTask#(e, d) }
            .return{ Void }
        })
      }

      .return{ Void }
    }
    """);
  }

  @Test void createToDoList() throws IOException, InterruptedException {
    createApp(BuildMode.PRODUCTION, """
    <!DOCTYPE html>
    <html lang="en">
    <head>
      <meta charset="UTF-8">
      <title>Todo List</title>
      <style>
        :root {
          --card: #ffffff;
          --muted: #6b7280;
          --accent: #3b82f6;
          --ok: #10b981;
          --glass: rgba(0, 0, 0, 0.02);
          --radius: 12px;
          --border: rgba(0, 0, 0, 0.08);
          font-family: Inter, system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
          color-scheme: light;
        }

        html, body {
          height: 100%;
          margin: 0;
          background: linear-gradient(180deg, #f2f5fb 0%, #e9eff7 100%);
          color: #111827;
        }

        .wrap {
          max-width: 760px;
          margin: 40px auto;
          padding: 28px;
          background: linear-gradient(180deg, var(--card), rgba(255, 255, 255, 0.95));
          border-radius: var(--radius);
          box-shadow: 0 8px 30px rgba(0, 0, 0, 0.05);
          backdrop-filter: blur(8px);
        }

        header {
          display: flex;
          align-items: center;
          gap: 16px;
          margin-bottom: 18px;
        }

        header h1 {
          font-size: 24px;
          margin: 0;
          color: #111827;
        }

        header .desc {
          margin: 4px 0 0;
          color: var(--muted);
          font-size: 13px;
        }

        .input-row {
          display: flex;
          gap: 8px;
          margin-bottom: 16px;
        }

        .input-row input[type="text"] {
          flex: 1;
          padding: 12px 14px;
          border-radius: 10px;
          border: 1px solid var(--border);
          background: var(--glass);
          color: inherit;
          font-size: 15px;
          outline: none;
          transition: border-color 0.15s ease, box-shadow 0.15s ease;
        }

        .input-row input[type="text"]:focus {
          border-color: var(--accent);
          box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.15);
        }

        .input-row input::placeholder {
          color: var(--muted);
        }

        .btn {
          padding: 10px 12px;
          border-radius: 10px;
          border: 0;
          background: linear-gradient(180deg, var(--accent), #2563eb);
          color: white;
          font-weight: 600;
          cursor: pointer;
          box-shadow: 0 4px 10px rgba(59, 130, 246, 0.15);
          transition: background 0.25s ease, transform 0.1s ease;
        }

        .btn:hover {
          background: linear-gradient(180deg, #4b8ff8, #1d4ed8);
        }

        .btn:active {
          transform: scale(0.97);
        }

        .btn.ghost {
          background: transparent;
          border: 1px solid var(--border);
          color: var(--muted);
          box-shadow: none;
        }

        .list {
          list-style: none;
          padding: 0;
          margin: 0;
          display: grid;
          gap: 8px;
        }

        .item {
          display: flex;
          align-items: center;
          gap: 12px;
          padding: 10px 12px;
          border-radius: 10px;
          background: #fafafa;
          border: 1px solid var(--border);
          transition: background 0.2s ease, box-shadow 0.2s ease;
        }

        .item:hover {
          background: #f3f4f6;
          box-shadow: 0 2px 6px rgba(0, 0, 0, 0.04);
        }

        .item .chk {
          width: 18px;
          height: 18px;
          border-radius: 6px;
          border: 1px solid var(--border);
          display: inline-grid;
          place-items: center;
          cursor: pointer;
          transition: background 0.2s ease, border-color 0.2s ease;
        }

        .item.done .chk {
          background: linear-gradient(180deg, var(--ok), #22c55e);
          border: 0;
          color: white;
        }

        .item .text {
          flex: 1;
          font-size: 15px;
          word-break: break-word;
          transition: color 0.2s ease;
        }

        .item.done .text {
          text-decoration: line-through;
          color: var(--muted);
        }

        .item button.icon {
          border: 0;
          background: transparent;
          color: var(--muted);
          cursor: pointer;
          padding: 6px;
          border-radius: 8px;
          transition: background 0.2s ease, color 0.2s ease;
        }

        .item button.icon:hover {
          background: rgba(0, 0, 0, 0.04);
          color: #dc2626;
        }

        @media (max-width: 520px) {
          .wrap {
            margin: 24px;
            padding: 18px;
          }
          header h1 {
            font-size: 18px;
          }
        }

        :focus {
          outline: 3px solid rgba(59, 130, 246, 0.2);
          outline-offset: 3px;
        }

        input:focus,
        button:focus {
          box-shadow: 0 4px 24px rgba(59, 130, 246, 0.06);
        }
      </style>
    </head>
    <body>
    <main class="wrap" role="main" aria-labelledby="title">
      <header>
        <div>
          <h1 id="title">Todo List</h1>
          <p class="desc">Click Add or press Enter to create a new task.</p>
        </div>
      </header>
    
      <div class="input-row" role="region" aria-label="Add todo">
        <input id="todoInput" type="text" placeholder="What do you want to do?" aria-label="New todo">
        <button id="addBtn" class="btn" title="Add (Enter)">Add</button>
      </div>
    
      <ul id="todoList" class="list" aria-live="polite"></ul>
    </main>
    </body>
    </html>
    """,
    "todolist/App_0.js",
    """
    package todolist
    alias base.Document as Document, alias base.Documents as Documents,
    alias base.Element as Element, alias base.Main as Main,
    alias base.Block as Block, alias base.Void as Void,
    alias base.F as F, alias base.Event as Event, alias base.Str as Str,
    alias base.Bool as Bool, alias base.True as True, alias base.False as False,
    
    ToggleDone: F[Event, mut Document, mut Element, Void] { ev, d, el -> Block#
      .let[Str] done = { el.getData("done") }
      .do{ el.setClass(done == "true" ? {.then -> "item", .else -> "item done" }) }
      .do{ el.setData("done", done == "true" ? {.then -> "false", .else -> "true" }) }
      .return{ Void }
    }
    
    AddTask: F[mut Event, mut Document, Void]{ e, dom -> Block#
      // obtain input and list elements
      .let[mut Element] input = { dom.getElementById("todoInput")! }
      .let[mut Element] list  = { dom.getElementById("todoList")! }
    
      // read text
      .let[Str] text = { input.value }
    
      // if empty, do nothing
      .if { text == "" } .return { Void }
    
      // create list item container
      .let[mut Element] li = { dom.createElement("li") }
      .do{ li.setClass("item") }
      .do{ li.setData("done", "false") }
    
      // create checkbox button
      .let[mut Element] chk = { dom.createElement("button") }
      .do{ chk.setClass("chk") }
      .do{ chk.setText("") }
    
      // create text div
      .let[mut Element] span = { dom.createElement("div") }
      .do{ span.setClass("text") }
      .do{ span.setText(text) }
    
      // create delete button
      .let[mut Element] delBtn = { dom.createElement("button") }
      .do{ delBtn.setText("x") }
      .do{ delBtn.setClass("icon") }
    
      // event bindings
      .do{ span.onClickWith(li, ToggleDone) }
      .do{ chk.onClickWith(li, ToggleDone) }
      .do{
        delBtn.onClickWith(li, { ev, d, el -> Block#
          .do{ el.remove }
          .return{ Void }
        })
      }
    
      // assemble and insert into list
      .do{ li.appendChild(chk) }
      .do{ li.appendChild(span) }
      .do{ li.appendChild(delBtn) }
      .do{ list.appendChild(li) }
    
      // reset and refocus input
      .do{ input.setValue("") }
      .do{ input.focus }
    
      .return{ Void }
    }
    
    App: Main{ s -> Block#
      .let[mut Document] dom = {Documents#s}
      .let[mut Element] addBtn = { dom.getElementById("addBtn")! }
      .let[mut Element] input  = { dom.getElementById("todoInput")! }
    
      // register add button click
      .do{ addBtn.onClick(AddTask) }
    
      // trigger AddTask on Enter key
      .do{
        input.onKeyDown({ e, d -> Block#
          .if { e.key == "Enter" } .do{ AddTask#(e, d) }
            .return{ Void }
        })
      }

      .do{ s.io.print("Todo List App initialized.") }
      .return{ Void }
    }
    
    """);
  }
}