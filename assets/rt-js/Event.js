import { rt$$Str } from "./Str.js";
import { base$$Void_0 } from "../base/Void_0.js";
import { rt$$Element } from "./Element.js";

/**
 * Runtime wrapper for DOM Event to match Fearless `Event` semantics.
 */
export class rt$$Event {
  /**
   * @param {Event} jsEvent - The native JavaScript event
   * @param {rt$$Element} element - The Fearless element that owns this event
   */
  constructor(jsEvent, element) {
    this.jsEvent = jsEvent;
    this.element = element;
  }

  /** read .eventType: Str -> Magic! */
  eventType$read$0() {
    return rt$$Str.fromJsStr(this.jsEvent.type || "");
  }

  /** mut .target: mut Element -> Magic! */
  target$mut$0() {
    // Return the wrapped target element if possible
    const target = this.jsEvent.target ?? this.element?.el;
    return new rt$$Element(target);
  }

  /** read .key: Str -> Magic! */
  key$read$0() {
    return rt$$Str.fromJsStr(this.jsEvent.key ?? "");
  }
}
