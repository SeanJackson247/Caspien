(* k-nucleotide: hand-written open-addressing table (linear probing, multiplicative hash); see knucleotide.c. *)
let ks = [| 1; 2; 3; 4; 6; 12 |]
let qk = [| 1; 1; 1; 1; 2; 2; 2; 3; 4; 6; 12 |]
let qv = [| 0; 1; 2; 3; 10; 11; 0; 43; 172; 2767; 11337487 |]
let golden = 0x9E3779B97F4A7C15L

(* top [bits] bits of the 64-bit product (kk * golden) *)
let hash kk bits =
  Int64.to_int (Int64.shift_right_logical (Int64.mul (Int64.of_int kk) golden) (64 - bits))

let lookup keys cnts bits cap kk =
  let h = ref (hash kk bits) and res = ref 0 and fin = ref false in
  while not !fin do
    let k = Array.unsafe_get keys !h in
    if k = 0 then fin := true
    else if k = kk then begin res := cnts.(!h); fin := true end
    else h := (!h + 1) land (cap - 1)
  done;
  !res

let () =
  let n = if Array.length Sys.argv > 1 then int_of_string Sys.argv.(1) else 30000000 in
  let seq = Bytes.create (n + 1) in
  let last = ref 42 in
  for i = 0 to n - 1 do
    last := (!last * 3877 + 29573) mod 139968;
    let l = !last in
    let c = if l < 42404 then 0 else if l < 70117 then 1 else if l < 97767 then 2 else 3 in
    Bytes.unsafe_set seq i (Char.unsafe_chr c)
  done;
  let first12 = ref 0 in
  for i = 0 to (if n < 12 then n else 12) - 1 do
    first12 := (!first12 lsl 2) lor Char.code (Bytes.get seq i)
  done;
  let distinct = Array.make 6 0 and counts = Array.make 12 0 in
  let nq = ref 0 in
  for ki = 0 to 5 do
    let k = ks.(ki) in
    let maxd = ref (1 lsl (2 * k)) in
    let win = if n >= k then n - k + 1 else 0 in
    if win < !maxd then maxd := win;
    if !maxd > 139968 then maxd := 139968;
    let bits = ref 1 in
    while (1 lsl !bits) < 2 * !maxd do incr bits done;
    let bits = !bits in
    let cap = 1 lsl bits in
    let keys = Array.make cap 0 and cnts = Array.make cap 0 in
    let mask = (1 lsl (2 * k)) - 1 in
    let key = ref 0 and nd = ref 0 in
    for i = 0 to n - 1 do
      key := ((!key lsl 2) lor Char.code (Bytes.unsafe_get seq i)) land mask;
      if i + 1 >= k then begin
        let kk = !key + 1 in
        let h = ref (hash kk bits) in
        let fin = ref false in
        while not !fin do
          let s = Array.unsafe_get keys !h in
          if s = 0 then begin
            keys.(!h) <- kk; cnts.(!h) <- 1; incr nd; fin := true
          end else if s = kk then begin
            cnts.(!h) <- cnts.(!h) + 1; fin := true
          end else h := (!h + 1) land (cap - 1)
        done
      end
    done;
    distinct.(ki) <- !nd;
    for q = 0 to 10 do
      if qk.(q) = k then begin
        counts.(!nq) <- lookup keys cnts bits cap (qv.(q) + 1);
        incr nq
      end
    done;
    if k = 12 then begin
      counts.(!nq) <- lookup keys cnts bits cap (!first12 + 1);
      incr nq
    end
  done;
  for i = 0 to 5 do Printf.printf "%d " distinct.(i) done;
  for i = 0 to 11 do
    if i > 0 then print_char ' ';
    Printf.printf "%d" counts.(i)
  done;
  print_char '\n'
