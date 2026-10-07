(* The word-count monoid, one character at a time. A word is counted where it
   starts, the only transition needing the previous character. The corpus is
   ASCII, so a character is a byte.

   Written as a tail-recursive fold over the string, which is OCaml's natural
   form and mirrors the Fearless fold over codepoints. Reading the file is inside
   the timed process on purpose: wall clock is the headline number and I/O is part
   of what the program costs. *)

let read_file path =
  let ic = open_in_bin path in
  let n = in_channel_length ic in
  let s = really_input_string ic n in
  close_in ic;
  s

let () =
  let s = read_file "corpus/corpus64.txt" in
  let len = String.length s in
  let rec go i lines words in_word =
    if i >= len then (lines, words)
    else
      match s.[i] with
      | '\n' -> go (i + 1) (lines + 1) words false
      | ' ' -> go (i + 1) lines words false
      | _ -> if in_word then go (i + 1) lines words true
             else go (i + 1) lines (words + 1) true
  in
  let lines, words = go 0 0 0 false in
  Printf.printf "%d %d %d\n" len lines words
