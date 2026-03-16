import { base$$Void_0 } from "../base/Void_0.js";

export class rt$$IO {
  static $self = new rt$$IO();

  printlnErr$mut$1(msg) {
    const msgJsStr = msg.toJsString();
    if (typeof process !== "undefined" && process.stdout) {
      process.stderr.write(msgJsStr + "\n");
    } else {
      console.error(msgJsStr + "\n");
    }
    return base$$Void_0.$self;
  }

  println$mut$1(msg) {
    const msgJsStr = msg.toJsString();
    if (typeof process !== "undefined" && process.stdout) {
      process.stdout.write(msgJsStr + "\n");
    } else {
      console.log(msgJsStr + "\n");
    }
    return base$$Void_0.$self;
  }

  print$mut$1(msg) {
    const msgJsStr = msg.toJsString();
    if (typeof process !== "undefined" && process.stdout) {
      process.stdout.write(msgJsStr);
    } else {
      console.log(msgJsStr);
    }
    return base$$Void_0.$self;
  }

  printErr$mut$1(msg) {
    const msgJsStr = msg.toJsString();
    if (typeof process !== "undefined" && process.stdout) {
      process.stderr.write(msgJsStr);
    } else {
      console.error(msgJsStr);
    }
    return base$$Void_0.$self;
  }

  accessR$mut$1(pathList) {
    // stub: implement like your rt.fs.ReadWritePath.readPath
    throw new Error("accessR$mut not implemented in JS runtime");
  }

  accessW$mut$1(pathList) {
    throw new Error("accessW$mut not implemented in JS runtime");
  }

  accessRW$mut$1(pathList) {
    throw new Error("accessRW$mut not implemented in JS runtime");
  }

  env$mut$0() {
    throw new Error("env$mut not implemented yet in JS runtime");
    // return Env.$self; // you’d define Env similar to IO
  }

  iso$mut$0() { return this; }
  self$mut$0() { return this; }

  // Equivalent of strListToPath
  static strListToPath(root, list) {
    throw new Error("strListToPath not implemented yet in JS runtime");
    // return list.reduce((acc, str) => acc + "/" + str.utf8(), root);
  }
}