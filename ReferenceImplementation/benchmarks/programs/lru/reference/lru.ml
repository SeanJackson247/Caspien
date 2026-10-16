(* LRU cache: chained buckets threaded through the node pool, index-based doubly linked list; see lru.c. *)
let cap = 262144
let keys_n = 1048576
let hot = 131072
let nb = 524288
let none = 0xFFFFFFFF

let x = ref 12345
let next () =
  x := (!x * 1664525 + 1013904223) land 0xFFFFFFFF;
  !x

let key = Array.make cap 0
let value = Array.make cap 0
let prv = Array.make cap 0
let nxt = Array.make cap 0
let hn = Array.make cap 0
let bucket = Array.make nb none
let head = ref none
let tail = ref none

let hsh k = ((k * 2654435761) land 0xFFFFFFFF) lsr 13

let unlink_node i =
  if prv.(i) <> none then nxt.(prv.(i)) <- nxt.(i) else head := nxt.(i);
  if nxt.(i) <> none then prv.(nxt.(i)) <- prv.(i) else tail := prv.(i)

let push_front i =
  prv.(i) <- none; nxt.(i) <- !head;
  if !head <> none then prv.(!head) <- i else tail := i;
  head := i

let find k =
  let i = ref bucket.(hsh k) in
  while !i <> none && key.(!i) <> k do i := hn.(!i) done;
  !i

let hash_remove i =
  let b = hsh key.(i) in
  if bucket.(b) = i then bucket.(b) <- hn.(i)
  else begin
    let j = ref bucket.(b) in
    while hn.(!j) <> i do j := hn.(!j) done;
    hn.(!j) <- hn.(i)
  end

let () =
  let n = if Array.length Sys.argv > 1 then int_of_string Sys.argv.(1) else 20000000 in
  let size = ref 0 and hits = ref 0 and misses = ref 0 and sum = ref 0 in
  for _ = 0 to n - 1 do
    let a = next () in
    let y = next () in
    let range = if (y lsr 20) mod 4 = 0 then keys_n else hot in
    let k = (a lsr 8) mod range in
    let i = find k in
    if (y lsr 24) mod 4 <> 0 then begin
      if i <> none then begin
        incr hits; sum := !sum + value.(i) + k; unlink_node i; push_front i
      end else incr misses
    end else if i <> none then begin
      value.(i) <- y; unlink_node i; push_front i
    end else begin
      let i =
        if !size = cap then begin
          let i = !tail in
          sum := !sum + key.(i); unlink_node i; hash_remove i; i
        end else begin
          let i = !size in incr size; i
        end
      in
      key.(i) <- k; value.(i) <- y;
      let b = hsh k in
      hn.(i) <- bucket.(b); bucket.(b) <- i;
      push_front i
    end
  done;
  let fin = ref 0 in
  let i = ref !head in
  while !i <> none do
    fin := (!fin * 31 + key.(!i)) land 0xFFFFFFFF;
    i := nxt.(!i)
  done;
  Printf.printf "%d %d %d\n" !hits !misses ((!sum + !fin) land 0xFFFFFFFF)
