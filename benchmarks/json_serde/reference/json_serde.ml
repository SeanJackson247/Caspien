(* JSON serialise + parse benchmark: see json_serde.c. Records are held as parallel (flat) int arrays. *)
let x = ref 12345
let next () =
  x := (!x * 1664525 + 1013904223) land 0xFFFFFFFF;
  !x

let wnum t p v =
  let d = ref 1 and u = ref v in
  while !u >= 10 do u := !u / 10; incr d done;
  let v = ref v in
  for k = !d - 1 downto 0 do
    Bytes.unsafe_set t (p + k) (Char.unsafe_chr (48 + !v mod 10));
    v := !v / 10
  done;
  p + !d

let wlit t p s =
  Bytes.blit_string s 0 t p (String.length s);
  p + String.length s

let () =
  let n = if Array.length Sys.argv > 1 then int_of_string Sys.argv.(1) else 4000000 in
  let text = Bytes.create (n * 80 + 16) in
  let p = ref 0 in
  Bytes.set text !p '['; incr p;
  for i = 0 to n - 1 do
    if i > 0 then begin Bytes.set text !p ','; incr p end;
    p := wlit text !p "{\"id\":"; p := wnum text !p i;
    p := wlit text !p ",\"name\":\"";
    let nl = 3 + (next () lsr 16) mod 8 in
    for _ = 1 to nl do
      Bytes.set text !p (Char.unsafe_chr (97 + (next () lsr 16) mod 26)); incr p
    done;
    p := wlit text !p "\",\"score\":";
    let cents = (next () lsr 8) mod 1000000 in
    p := wnum text !p (cents / 100);
    Bytes.set text !p '.'; incr p;
    Bytes.set text !p (Char.unsafe_chr (48 + cents mod 100 / 10)); incr p;
    Bytes.set text !p (Char.unsafe_chr (48 + cents mod 10)); incr p;
    p := wlit text !p ",\"tags\":[";
    let tc = (next () lsr 16) mod 5 in
    for k = 0 to tc - 1 do
      if k > 0 then begin Bytes.set text !p ','; incr p end;
      p := wnum text !p ((next () lsr 16) mod 100)
    done;
    p := wlit text !p "]}"
  done;
  Bytes.set text !p ']'; incr p;
  let tlen = !p in
  (* ---- parse ---- *)
  let r_id = Array.make (n + 1) 0 and r_noff = Array.make (n + 1) 0 and r_nlen = Array.make (n + 1) 0
  and r_score = Array.make (n + 1) 0 and r_toff = Array.make (n + 1) 0 and r_tcnt = Array.make (n + 1) 0 in
  let names = Bytes.create (n * 10 + 8) in
  let tags = Bytes.create (n * 4 + 8) in
  let count = ref 0 and noff = ref 0 and toff = ref 0 and q = ref 1 in
  let digits () =
    let v = ref 0 in
    while (let c = Bytes.unsafe_get text !q in c >= '0' && c <= '9') do
      v := !v * 10 + (Char.code (Bytes.unsafe_get text !q) - 48); incr q
    done;
    !v
  in
  let fin = ref false in
  while not !fin do
    let c = Bytes.get text !q in
    if c = ']' then fin := true
    else if c = ',' then incr q
    else begin
      incr q;
      let id = ref 0 and name_off = ref 0 and name_len = ref 0 and score = ref 0 and tag_off = ref 0 and tag_cnt = ref 0 in
      let more = ref true in
      while !more do
        incr q;
        let k0 = Bytes.get text !q in
        while Bytes.get text !q <> '"' do incr q done;
        q := !q + 2;
        if k0 = 'i' then id := digits ()
        else if k0 = 'n' then begin
          incr q;
          name_off := !noff;
          while Bytes.get text !q <> '"' do
            Bytes.unsafe_set names !noff (Bytes.unsafe_get text !q); incr noff; incr q
          done;
          name_len := !noff - !name_off;
          incr q
        end else if k0 = 's' then begin
          let v = digits () in
          incr q;
          score := v * 100 + (Char.code (Bytes.get text !q) - 48) * 10 + (Char.code (Bytes.get text (!q + 1)) - 48);
          q := !q + 2
        end else begin
          incr q;
          tag_off := !toff;
          while Bytes.get text !q <> ']' do
            if Bytes.get text !q = ',' then incr q;
            let v = digits () in
            Bytes.unsafe_set tags !toff (Char.unsafe_chr (v land 255)); incr toff
          done;
          tag_cnt := !toff - !tag_off;
          incr q
        end;
        if Bytes.get text !q = ',' then incr q else begin incr q; more := false end
      done;
      let c = !count in
      r_id.(c) <- !id; r_noff.(c) <- !name_off; r_nlen.(c) <- !name_len; r_score.(c) <- !score;
      r_toff.(c) <- !tag_off; r_tcnt.(c) <- !tag_cnt;
      count := c + 1
    end
  done;
  let h = ref 7 in
  for i = 0 to !count - 1 do
    h := (!h * 31 + r_id.(i)) land 0xFFFFFFFF;
    h := (!h * 31 + r_score.(i)) land 0xFFFFFFFF;
    h := (!h * 31 + r_nlen.(i)) land 0xFFFFFFFF;
    for k = 0 to r_nlen.(i) - 1 do
      h := (!h * 31 + Char.code (Bytes.unsafe_get names (r_noff.(i) + k))) land 0xFFFFFFFF
    done;
    h := (!h * 31 + r_tcnt.(i)) land 0xFFFFFFFF;
    for k = 0 to r_tcnt.(i) - 1 do
      h := (!h * 31 + Char.code (Bytes.unsafe_get tags (r_toff.(i) + k))) land 0xFFFFFFFF
    done
  done;
  Printf.printf "%d %d %d\n" !count tlen !h
