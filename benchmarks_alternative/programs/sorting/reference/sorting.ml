(* Sorting and searching benchmark: see sorting.c. *)
let x = ref 12345
let next () =
  x := (!x * 1664525 + 1013904223) land 0xFFFFFFFF;
  !x

let quicksort (a : int array) n =
  let stack = Array.make 128 0 in
  let sp = ref 0 in
  stack.(!sp) <- 0; incr sp;
  stack.(!sp) <- n - 1; incr sp;
  while !sp > 0 do
    decr sp; let hi0 = stack.(!sp) in
    decr sp; let lo0 = stack.(!sp) in
    let lo = ref lo0 and hi = ref hi0 in
    while !lo < !hi do
      let pivot = a.((!lo + !hi) / 2) in
      let i = ref !lo and j = ref !hi in
      while !i <= !j do
        while a.(!i) < pivot do incr i done;
        while a.(!j) > pivot do decr j done;
        if !i <= !j then begin
          let t = a.(!i) in
          a.(!i) <- a.(!j); a.(!j) <- t;
          incr i; decr j
        end
      done;
      if !j - !lo < !hi - !i then begin
        stack.(!sp) <- !i; incr sp;
        stack.(!sp) <- !hi; incr sp;
        hi := !j
      end else begin
        stack.(!sp) <- !lo; incr sp;
        stack.(!sp) <- !j; incr sp;
        lo := !i
      end
    done
  done

let mergesort (a : int array) (tmp : int array) n =
  let src = ref a and dst = ref tmp in
  let w = ref 1 in
  while !w < n do
    let s = !src and d = !dst in
    let lo = ref 0 in
    while !lo < n do
      let mid = if !lo + !w < n then !lo + !w else n in
      let hi = if !lo + 2 * !w < n then !lo + 2 * !w else n in
      let i = ref !lo and j = ref mid and k = ref !lo in
      while !i < mid && !j < hi do
        if s.(!i) <= s.(!j) then begin d.(!k) <- s.(!i); incr i end
        else begin d.(!k) <- s.(!j); incr j end;
        incr k
      done;
      while !i < mid do d.(!k) <- s.(!i); incr i; incr k done;
      while !j < hi do d.(!k) <- s.(!j); incr j; incr k done;
      lo := !lo + 2 * !w
    done;
    src := d; dst := s;
    w := !w * 2
  done;
  if !src != a then Array.blit !src 0 a 0 n

let siftdown (a : int array) root0 n =
  let root = ref root0 in
  let continue = ref true in
  while !continue do
    let c = ref (2 * !root + 1) in
    if !c >= n then continue := false
    else begin
      if !c + 1 < n && a.(!c + 1) > a.(!c) then incr c;
      if a.(!root) >= a.(!c) then continue := false
      else begin
        let t = a.(!root) in
        a.(!root) <- a.(!c); a.(!c) <- t;
        root := !c
      end
    end
  done

let heapsort (a : int array) n =
  for i = n / 2 - 1 downto 0 do siftdown a i n done;
  for e = n - 1 downto 1 do
    let t = a.(0) in
    a.(0) <- a.(e); a.(e) <- t;
    siftdown a 0 e
  done

let bsearch_has (a : int array) n key =
  let lo = ref 0 and hi = ref n in
  while !lo < !hi do
    let m = (!lo + !hi) / 2 in
    if a.(m) < key then lo := m + 1 else hi := m
  done;
  !lo < n && a.(!lo) = key

let () =
  let n = if Array.length Sys.argv > 1 then int_of_string Sys.argv.(1) else 2000000 in
  let orig = Array.make n 0 in
  for i = 0 to n - 1 do orig.(i) <- next () lsr 1 done;
  let a = Array.copy orig and b = Array.copy orig and d = Array.copy orig in
  let tmp = Array.make n 0 in
  quicksort a n;
  mergesort b tmp n;
  heapsort d n;
  let h = ref 0 and ok = ref true in
  for i = 0 to n - 1 do
    h := (!h * 31 + a.(i)) land 0xFFFFFFFF;
    if a.(i) <> b.(i) || a.(i) <> d.(i) then ok := false;
    if i > 0 && a.(i - 1) > a.(i) then ok := false
  done;
  let bs = ref 0 in
  for k = 0 to n - 1 do
    let key = next () lsr 1 in
    let key = if k mod 2 = 0 then orig.(key mod n) else key in
    if bsearch_has a n key then incr bs
  done;
  let ls = ref 0 in
  for k = 0 to 19 do
    let key = next () lsr 1 in
    let key = if k mod 2 = 0 then orig.(key mod n) else key in
    let i = ref 0 in
    while !i < n && orig.(!i) <> key do incr i done;
    if !i < n then incr ls
  done;
  Printf.printf "%d %d %d %s\n" !h !bs !ls (if !ok then "OK" else "BAD")
