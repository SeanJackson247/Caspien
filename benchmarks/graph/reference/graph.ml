(* Heap graph benchmark: see graph.c. One heap block per node. *)
type node = {
  mutable value : int;
  mutable dist : int;
  mutable e0 : node;
  mutable e1 : node;
  mutable e2 : node;
  mutable e3 : node;
}

let rec dummy = { value = 0; dist = -1; e0 = dummy; e1 = dummy; e2 = dummy; e3 = dummy }

let x = ref 12345
let next () =
  x := (!x * 1664525 + 1013904223) land 0xFFFFFFFF;
  !x

let () =
  let n = if Array.length Sys.argv > 1 then int_of_string Sys.argv.(1) else 2000000 in
  let nodes = Array.make n dummy in
  for i = 0 to n - 1 do
    let v = next () land 0xFFFFFF in
    nodes.(i) <- { value = v; dist = -1; e0 = dummy; e1 = dummy; e2 = dummy; e3 = dummy }
  done;
  nodes.(n * 7 / 10).value <- 0xFFFFFFFF;
  for i = 0 to n - 1 do
    let u = nodes.(i) in
    u.e0 <- nodes.((i + 1) mod n);
    u.e1 <- nodes.(next () mod n);
    u.e2 <- nodes.(next () mod n);
    u.e3 <- nodes.(next () mod n)
  done;
  let q = Array.make n dummy in
  let head = ref 0 and tail = ref 0 in
  q.(0) <- nodes.(0); tail := 1; nodes.(0).dist <- 0;
  let maxdepth = ref 0 and needledist = ref (-1) and sum = ref 0 in
  let visit u v =
    if v.dist < 0 then begin
      v.dist <- u.dist + 1;
      q.(!tail) <- v;
      incr tail
    end
  in
  while !head < !tail do
    let u = q.(!head) in
    incr head;
    sum := (!sum + u.value) land 0xFFFFFFFF;
    if u.value = 0xFFFFFFFF then needledist := u.dist;
    if u.dist > !maxdepth then maxdepth := u.dist;
    visit u u.e0; visit u u.e1; visit u u.e2; visit u u.e3
  done;
  Printf.printf "%d %d %d %d\n" !tail !maxdepth !needledist !sum
