(* String manipulation benchmark: see strings.c. *)
let x = ref 12345
let next () =
  x := (!x * 1664525 + 1013904223) land 0xFFFFFFFF;
  !x

let hash b n =
  let h = ref 7 in
  for i = 0 to n - 1 do
    h := (!h * 31 + Char.code (Bytes.get b i)) land 0xFFFFFFFF
  done;
  !h

let () =
  let n = if Array.length Sys.argv > 1 then int_of_string Sys.argv.(1) else 4000000 in
  let text = Bytes.create n in
  for i = 0 to n - 1 do
    let r = (next () lsr 16) mod 27 in
    Bytes.set text i (if r = 26 then ' ' else Char.chr (97 + r))
  done;
  let words = ref 0 and longest = ref 0 and cur = ref 0 in
  for i = 0 to n - 1 do
    if Bytes.get text i = ' ' then begin
      if !cur > 0 then begin
        incr words;
        if !cur > !longest then longest := !cur;
        cur := 0
      end
    end else incr cur
  done;
  if !cur > 0 then begin
    incr words;
    if !cur > !longest then longest := !cur
  end;
  let abc = ref 0 in
  for i = 0 to n - 3 do
    if Bytes.get text i = 'a' && Bytes.get text (i + 1) = 'b' && Bytes.get text (i + 2) = 'c' then incr abc
  done;
  let rev = Bytes.create n in
  for i = 0 to n - 1 do
    Bytes.set rev i (Bytes.get text (n - 1 - i))
  done;
  let hrev = hash rev n in
  let es = ref 0 in
  for i = 0 to n - 1 do
    if Bytes.get text i = 'e' then incr es
  done;
  let m = n + !es in
  let rep = Bytes.create m in
  let k = ref 0 in
  for i = 0 to n - 1 do
    let c = Bytes.get text i in
    if c = 'e' then begin
      Bytes.set rep !k '3'; Bytes.set rep (!k + 1) '3'; k := !k + 2
    end else begin
      Bytes.set rep !k c; incr k
    end
  done;
  let hrep = hash rep m in
  let up = Bytes.create n in
  for i = 0 to n - 1 do
    let c = Bytes.get text i in
    Bytes.set up i (if c = ' ' then ' ' else Char.chr (Char.code c - 32))
  done;
  let hup = hash up n in
  Printf.printf "%d %d %d %d %d %d %d\n" !words !longest !abc hrev m hrep hup
