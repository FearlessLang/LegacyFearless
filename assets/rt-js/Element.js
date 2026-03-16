import { base$$Void_0 } from "../base/Void_0.js";
import { rt$$Event } from "./Event.js";
import { rt$$Document } from "./Document.js";
import { rt$$Str } from "./Str.js";

/**
 * Runtime wrapper for a DOM Element, providing Fearless-compatible methods.
 */
export class rt$$Element {
  constructor(domElement) {
    this.el = domElement;
  }

  /** read .value: Str -> Magic! */
  value$read$0() {
    return rt$$Str.fromJsStr(this.el.value ?? "");
  }

  /** mut .setValue(v: Str): Void -> Magic! */
  setValue$mut$1(v_m$) {
    this.el.value = v_m$.toJsString();
    return base$$Void_0.$self;
  }

  /** mut .setText(v: Str): Void -> Magic! */
  setText$mut$1(v_m$) {
    this.el.textContent = v_m$.toJsString();
    return base$$Void_0.$self;
  }

  /** mut .appendChild(child: mut Element): Void -> Magic! */
  appendChild$mut$1(child_m$) {
    this.el.appendChild(child_m$.el);
    return base$$Void_0.$self;
  }

  /** mut .setClass(v: Str): Void -> Magic! */
  setClass$mut$1(v_m$) {
    this.el.className = v_m$.toJsString();
    return base$$Void_0.$self;
  }

  /** mut .setAttr(name: Str, value: Str): Void -> Magic! */
  setAttr$mut$2(name_m$, value_m$) {
    this.el.setAttribute(name_m$.toJsString(), value_m$.toJsString());
    return base$$Void_0.$self;
  }

  /** mut .setData(key: Str, value: Str): Void -> Magic! */
  setData$mut$2(key_m$, value_m$) {
    this.el.dataset[key_m$.toJsString()] = value_m$.toJsString();
    return base$$Void_0.$self;
  }

  /** read .getData(key: Str): Str -> Magic! */
  getData$read$1(key_m$) {
    const v = this.el.dataset[key_m$.toJsString()] ?? "";
    return rt$$Str.fromJsStr(v);
  }

  /** mut .replaceChild(newEl: mut Element, oldEl: mut Element): Void -> Magic! */
  replaceChild$mut$2(new_m$, old_m$) {
    this.el.replaceChild(new_m$.el, old_m$.el);
    return base$$Void_0.$self;
  }

  /** mut .select: Void -> Magic! */
  select$mut$0() {
    if (this.el.select) this.el.select();
    return base$$Void_0.$self;
  }

  /** mut .remove: Void -> Magic! */
  remove$mut$0() {
    this.el.remove();
    return base$$Void_0.$self;
  }

  /** mut .focus: Void -> Magic! */
  focus$mut$0() {
    this.el.focus();
    return base$$Void_0.$self;
  }

  // ─── Event handlers ─────────────────────────────

  /** internal helper for all event bindings */
  _bind(eventName, callback_m$) {
    this.el.addEventListener(eventName, (jsEvent) => {
      const ev = new rt$$Event(jsEvent, this);
      callback_m$.$hash$read$2(ev, rt$$Document.$self);
    });
    return base$$Void_0.$self;
  }

  /** mut .onClick(callback: F[mut Event, mut Document, Void]): Void -> Magic! */
  onClick$mut$1(callback_m$) {
    return this._bind("click", callback_m$);
  }

  /** mut .onInput(callback: F[mut Event, mut Document, Void]): Void -> Magic! */
  onInput$mut$1(callback_m$) {
    return this._bind("input", callback_m$);
  }

  /** mut .onKeyDown(callback: F[mut Event, mut Document, Void]): Void -> Magic! */
  onKeyDown$mut$1(callback_m$) {
    return this._bind("keydown", callback_m$);
  }

  /** mut .onDblClick(callback: F[mut Event, mut Document, Void]): Void -> Magic! */
  onDblClick$mut$1(callback_m$) {
    return this._bind("dblclick", callback_m$);
  }

  /** mut .onBlur(callback: F[mut Event, mut Document, Void]): Void -> Magic! */
  onBlur$mut$1(callback_m$) {
    return this._bind("blur", callback_m$);
  }

  /** mut .addEventListener(event: Str, callback: F[Event, mut Document, Void]): Void -> Magic! */
  addEventListener$mut$2(event_m$, callback_m$) {
    const name = event_m$.toJsString();
    return this._bind(name, callback_m$);
  }

  /** mut .onClickWith[R:**](result: mut R, callback: F[Event,mut Document,mut R,Void]): Void -> Magic! */
  onClickWith$mut$2(result_m$, callback_m$) {
    this.el.addEventListener("click", (jsEvent) => {
      const ev = new rt$$Event(jsEvent, this);
      callback_m$.$hash$read$3(ev, rt$$Document.$self, result_m$);
    });
    return base$$Void_0.$self;
  }
}
