(* Merkle tree with real SHA-256 (FIPS 180-4); see merkletrees.c. 32-bit words held in native ints, masked to 32 bits. *)
let x = ref 12345
let next () =
  x := (!x * 1664525 + 1013904223) land 0xFFFFFFFF;
  !x

let k = [|
  0x428a2f98;0x71374491;0xb5c0fbcf;0xe9b5dba5;0x3956c25b;0x59f111f1;0x923f82a4;0xab1c5ed5;
  0xd807aa98;0x12835b01;0x243185be;0x550c7dc3;0x72be5d74;0x80deb1fe;0x9bdc06a7;0xc19bf174;
  0xe49b69c1;0xefbe4786;0x0fc19dc6;0x240ca1cc;0x2de92c6f;0x4a7484aa;0x5cb0a9dc;0x76f988da;
  0x983e5152;0xa831c66d;0xb00327c8;0xbf597fc7;0xc6e00bf3;0xd5a79147;0x06ca6351;0x14292967;
  0x27b70a85;0x2e1b2138;0x4d2c6dfc;0x53380d13;0x650a7354;0x766a0abb;0x81c2c92e;0x92722c85;
  0xa2bfe8a1;0xa81a664b;0xc24b8b70;0xc76c51a3;0xd192e819;0xd6990624;0xf40e3585;0x106aa070;
  0x19a4c116;0x1e376c08;0x2748774c;0x34b0bcb5;0x391c0cb3;0x4ed8aa4a;0x5b9cca4f;0x682e6ff3;
  0x748f82ee;0x78a5636f;0x84c87814;0x8cc70208;0x90befffa;0xa4506ceb;0xbef9a3f7;0xc67178f2 |]
let h0 = [| 0x6a09e667;0xbb67ae85;0x3c6ef372;0xa54ff53a;0x510e527f;0x9b05688c;0x1f83d9ab;0x5be0cd19 |]
let m32 = 0xFFFFFFFF

let[@inline] ror v n = ((v lsr n) lor (v lsl (32 - n))) land m32

let w = Array.make 64 0

(* st (8 words) updated in place with the 16-word block b *)
let compress (st : int array) (b : int array) =
  for i = 0 to 15 do w.(i) <- b.(i) done;
  for i = 16 to 63 do
    let w15 = w.(i - 15) and w2 = w.(i - 2) in
    let s0 = ror w15 7 lxor ror w15 18 lxor (w15 lsr 3) in
    let s1 = ror w2 17 lxor ror w2 19 lxor (w2 lsr 10) in
    w.(i) <- (w.(i - 16) + s0 + w.(i - 7) + s1) land m32
  done;
  let a = ref st.(0) and bb = ref st.(1) and c = ref st.(2) and d = ref st.(3)
  and e = ref st.(4) and f = ref st.(5) and g = ref st.(6) and h = ref st.(7) in
  for i = 0 to 63 do
    let ev = !e and av = !a in
    let s1 = ror ev 6 lxor ror ev 11 lxor ror ev 25 in
    let ch = (ev land !f) lxor ((lnot ev) land m32 land !g) in
    let t1 = (!h + s1 + ch + k.(i) + w.(i)) land m32 in
    let s0 = ror av 2 lxor ror av 13 lxor ror av 22 in
    let mj = (av land !bb) lxor (av land !c) lxor (!bb land !c) in
    let t2 = (s0 + mj) land m32 in
    h := !g; g := !f; f := ev; e := (!d + t1) land m32; d := !c; c := !bb; bb := av; a := (t1 + t2) land m32
  done;
  st.(0) <- (st.(0) + !a) land m32; st.(1) <- (st.(1) + !bb) land m32;
  st.(2) <- (st.(2) + !c) land m32; st.(3) <- (st.(3) + !d) land m32;
  st.(4) <- (st.(4) + !e) land m32; st.(5) <- (st.(5) + !f) land m32;
  st.(6) <- (st.(6) + !g) land m32; st.(7) <- (st.(7) + !h) land m32

let bswap32 v = ((v lsr 24) lor ((v lsr 8) land 0xff00) lor ((v lsl 8) land 0xff0000) lor (v lsl 24)) land m32

let lb = Array.make 16 0
let st = Array.make 8 0
let nb = Array.make 16 0
let pad = Array.make 16 0

(* out[oo..oo+8] = SHA256(le64(dlo + 2^32*dhi) || le64(i)) *)
let leaf (out : int array) oo dlo dhi i =
  lb.(0) <- bswap32 dlo; lb.(1) <- bswap32 dhi;
  lb.(2) <- bswap32 (i land m32); lb.(3) <- bswap32 ((i lsr 32) land m32);
  lb.(4) <- 0x80000000;
  for j = 5 to 14 do lb.(j) <- 0 done;
  lb.(15) <- 128;
  for j = 0 to 7 do st.(j) <- h0.(j) done;
  compress st lb;
  for j = 0 to 7 do out.(oo + j) <- st.(j) done

(* out = SHA256(l || r); out may alias l or r *)
let node (out : int array) oo (l : int array) lo (r : int array) ro =
  for j = 0 to 7 do nb.(j) <- l.(lo + j); nb.(8 + j) <- r.(ro + j); st.(j) <- h0.(j) done;
  compress st nb;
  pad.(0) <- 0x80000000;
  for j = 1 to 14 do pad.(j) <- 0 done;
  pad.(15) <- 512;
  compress st pad;
  for j = 0 to 7 do out.(oo + j) <- st.(j) done

let () =
  let n = if Array.length Sys.argv > 1 then int_of_string Sys.argv.(1) else 140000 in
  let data = Array.make n 0 in
  let t = Array.make ((2 * n + 64) * 8) 0 in
  for i = 0 to n - 1 do
    data.(i) <- next ();
    leaf t (8 * i) data.(i) 0 i
  done;
  let off = ref 0 and size = ref n in
  while !size > 1 do
    let noff = !off + !size and ns = (!size + 1) / 2 in
    for j = 0 to ns - 1 do
      let lo = 8 * (!off + 2 * j) in
      let ro = if 2 * j + 1 < !size then 8 * (!off + 2 * j + 1) else lo in
      node t (8 * (noff + j)) t lo t ro
    done;
    off := noff; size := ns
  done;
  let root = 8 * !off in
  let verified = ref 0 and rejected = ref 0 in
  let sibs = Array.make (64 * 8) 0 and cur = Array.make 8 0 in
  for p = 0 to n / 2 - 1 do
    let idx = next () mod n in
    let o = ref 0 and s = ref n and pos = ref idx and depth = ref 0 in
    while !s > 1 do
      let sb = if !pos mod 2 = 0 then !pos + 1 else !pos - 1 in
      let sb = if sb >= !s then !pos else sb in
      for j = 0 to 7 do sibs.(!depth * 8 + j) <- t.(8 * (!o + sb) + j) done;
      incr depth;
      o := !o + !s; s := (!s + 1) / 2; pos := !pos / 2
    done;
    let dlo = ref data.(idx) and dhi = ref 0 in
    if p mod 4 = 3 then begin
      dlo := (!dlo + 1) land m32;
      dhi := if !dlo = 0 then 1 else 0
    end;
    leaf cur 0 !dlo !dhi idx;
    pos := idx;
    for d = 0 to !depth - 1 do
      if !pos mod 2 = 0 then node cur 0 cur 0 sibs (8 * d) else node cur 0 sibs (8 * d) cur 0;
      pos := !pos / 2
    done;
    let eq = ref true in
    for j = 0 to 7 do if cur.(j) <> t.(root + j) then eq := false done;
    if !eq then incr verified else incr rejected
  done;
  for j = 0 to 7 do Printf.printf "%08x" t.(root + j) done;
  Printf.printf " %d %d\n" !verified !rejected
