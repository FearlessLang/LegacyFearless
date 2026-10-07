(* Counts the n-queens solutions.

   The board is a record of three int bitmasks; the highest bit used is row + n,
   which is 30 here and fits OCaml's native int with room to spare. An absent
   placement is [None]: OCaml's option is a real allocation for [Some], which is
   the same cost the Swift and Fearless versions pay before FeaRT's niche
   representation removes it. The binary column split follows the Fearless
   source. *)

type board = { cols : int; d1 : int; d2 : int }

let n = 15

let attacked b row c =
  b.cols land (1 lsl c) <> 0
  || b.d1 land (1 lsl (row + c)) <> 0
  || b.d2 land (1 lsl (row + n - c)) <> 0

let place b row c =
  if attacked b row c then None
  else
    Some
      { cols = b.cols lor (1 lsl c);
        d1 = b.d1 lor (1 lsl (row + c));
        d2 = b.d2 lor (1 lsl (row + n - c)) }

let mid lo hi = lo + ((hi - lo) / 2)

let rec solve b row = if row >= n then 1 else try_cols b row 0 n

and leaf b row c =
  match place b row c with Some next -> solve next (row + 1) | None -> 0

and try_cols b row lo hi =
  if lo >= hi then 0
  else if hi - lo <= 1 then leaf b row lo
  else try_cols b row lo (mid lo hi) + try_cols b row (mid lo hi) hi

let () = Printf.printf "%d\n" (solve { cols = 0; d1 = 0; d2 = 0 } 0)
