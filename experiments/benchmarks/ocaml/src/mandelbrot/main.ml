(* Escape-time Mandelbrot, summing every pixel's iteration count.

   The complex number is a record of two floats, which OCaml stores unboxed as a
   flat float record. [escape] is tail recursive, so it compiles to a loop. *)

type complex = { re : float; im : float }

let w = 6600
let h = 6600
let max_iter = 256

let add a b = { re = a.re +. b.re; im = a.im +. b.im }
let sq a = { re = (a.re *. a.re) -. (a.im *. a.im); im = 2.0 *. a.re *. a.im }
let norm2 a = (a.re *. a.re) +. (a.im *. a.im)

(* The cap is tested before the escape condition, which is what fixes the
   checksum. *)
let rec escape c z i =
  if i >= max_iter then max_iter
  else if norm2 z > 4.0 then i
  else escape c (add (sq z) c) (i + 1)

(* Window: real [-2.5, 1.0), imaginary [-1.0, 1.0). *)
let point idx =
  { re = (float_of_int (idx mod w) *. 3.5 /. float_of_int w) -. 2.5;
    im = (float_of_int (idx / w) *. 2.0 /. float_of_int h) -. 1.0 }

let leaf idx = escape (point idx) { re = 0.0; im = 0.0 } 0

let mid lo hi = lo + ((hi - lo) / 2)

let rec solve lo hi =
  if hi - lo <= 1 then leaf lo
  else solve lo (mid lo hi) + solve (mid lo hi) hi

let () = Printf.printf "%d\n" (solve 0 (w * h))
