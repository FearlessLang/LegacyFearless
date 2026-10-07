(* Counts the primes below n by trial division. Per-element cost grows with the
   element: the range is badly unbalanced.

   The binary divisor split follows the Fearless source, and [&&] short-circuits
   so the early exit on composites survives it. The divisor set is kept exactly as
   the Fearless source tests it: every integer in [3, limit), not only the odd
   ones. *)

let n = 17_000_000

(* One past the largest divisor worth testing. Two rather than one absorbs a
   rounding error of a whole unit at a perfect square. *)
let limit m = int_of_float (Float.floor (sqrt (float_of_int m))) + 2

let mid lo hi = lo + ((hi - lo) / 2)

let rec no_divisor_in m lo hi =
  if lo >= hi then true
  else if hi - lo <= 1 then m mod lo <> 0
  else no_divisor_in m lo (mid lo hi) && no_divisor_in m (mid lo hi) hi

let is_prime m =
  if m < 4 then true
  else if m mod 2 = 0 then false
  else no_divisor_in m 3 (limit m)

let () =
  let count = ref 0 in
  for c = 2 to n - 1 do
    if is_prime c then incr count
  done;
  Printf.printf "%d\n" !count
