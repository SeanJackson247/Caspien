(* FASTA benchmark into one heap buffer: see fasta.c. *)
let alu = "GGCCGGGCGCGGTGGCTCACGCCTGTAATCCCAGCACTTTGGGAGGCCGAGGCGGGCGGATCACCTGAGGTCAGGAGTTCGAGACCAGCCTGGCCAACATGGTGAAACCCCGTCTCTACTAAAAATACAAAAATTAGCCGGGCGTGGTGGCGCGCGCCTGTAATCCCAGCTACTCGGGAGGCTGAGGCAGGAGAATCGCTTGAACCCGGGAGGCGGAGGTTGCAGTGAGCCGAGATCGCGCCACTGCACTCCAGCCTGGGCGACAGAGCGAGACTCCGTCTCAAAAA"
let chars = "ACGTBDHKMNRSVWYACGT"
let thr = [| 37792;54588;71384;109176;111975;114774;117574;120373;123172;125972;128771;131570;134370;137169;139968;42404;70117;97767;139968 |]
let hdr = [| ">ONE Homo sapiens alu\n"; ">TWO IUB ambiguity codes\n"; ">THREE Homo sapiens frequency\n" |]

let () =
  let n = if Array.length Sys.argv > 1 then int_of_string Sys.argv.(1) else 40000000 in
  let cnt = [| n * 2 / 10; n * 3 / 10; 0 |] in
  cnt.(2) <- n - cnt.(0) - cnt.(1);
  let off = [| 0; 0; 15 |] in
  let buf = Bytes.create (n + n / 60 + 1024) in
  let last = ref 42 in
  let pos = ref 0 and a = ref 0 and c = ref 0 and g = ref 0 and t = ref 0 and other = ref 0 and ai = ref 0 in
  for s = 0 to 2 do
    let h = hdr.(s) in
    Bytes.blit_string h 0 buf !pos (String.length h);
    pos := !pos + String.length h;
    let col = ref 0 in
    for _ = 1 to cnt.(s) do
      let ch =
        if s = 0 then begin
          let ch = String.unsafe_get alu !ai in
          incr ai;
          if !ai = 287 then ai := 0;
          ch
        end else begin
          last := (!last * 3877 + 29573) mod 139968;
          let j = ref off.(s) in
          while !last >= thr.(!j) do incr j done;
          String.unsafe_get chars !j
        end
      in
      Bytes.set buf !pos ch; incr pos;
      (match ch with
       | 'A' -> incr a | 'C' -> incr c | 'G' -> incr g | 'T' -> incr t | _ -> incr other);
      incr col;
      if !col = 60 then begin Bytes.set buf !pos '\n'; incr pos; col := 0 end
    done;
    if !col > 0 then begin Bytes.set buf !pos '\n'; incr pos end
  done;
  let h = ref 7 in
  for i = 0 to !pos - 1 do
    h := (!h * 31 + Char.code (Bytes.get buf i)) land 0xFFFFFFFF
  done;
  Printf.printf "%d %d %d %d %d %d %d\n" !pos !h !a !c !g !t !other
