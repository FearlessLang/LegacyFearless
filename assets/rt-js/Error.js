import { FearlessError} from "./FearlessError.js";

export const rt$$Error = {
  throwFearlessError(info) {
    throw new FearlessError(info);
  }
};
