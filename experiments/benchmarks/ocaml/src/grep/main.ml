(* Counts corpus lines containing "needle". A literal rather than a regex: what is
   measured is scanning. The Stdlib has no substring search, so the automaton is
   written out, as an OCaml programmer would.

   Every proper prefix of "needle" has an empty border, so on a mismatch the
   automaton restarts at zero and only re-tests the current character against the
   needle's first. Exact for this literal, not for needles in general. *)

let needle = "needle"

let read_file path =
  let ic = open_in_bin path in
  let n = in_channel_length ic in
  let s = really_input_string ic n in
  close_in ic;
  s

let () =
  let s = read_file "corpus/corpus64.txt" in
  let len = String.length s in
  let nlen = String.length needle in
  let rec go i matched hit count =
    if i >= len then count
    else if s.[i] = '\n' then
      go (i + 1) 0 false (if hit then count + 1 else count)
    else
      let next =
        if s.[i] = needle.[matched] then matched + 1
        else if s.[i] = needle.[0] then 1
        else 0
      in
      if next = nlen then go (i + 1) 0 true count
      else go (i + 1) next hit count
  in
  Printf.printf "%d\n" (go 0 0 false 0)
