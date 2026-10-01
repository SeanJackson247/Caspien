(* Binary trees: ordinary heap nodes with left/right references, one allocation per node (see binarytrees.c). *)
type t = Nil | Node of t * t

let rec make d =
  if d > 0 then begin
    let l = make (d - 1) in
    let r = make (d - 1) in
    Node (l, r)
  end else Node (Nil, Nil)

let rec check = function
  | Nil -> 0
  | Node (Nil, _) -> 1
  | Node (l, r) -> 1 + check l + check r

let () =
  let maxd = if Array.length Sys.argv > 1 then int_of_string Sys.argv.(1) else 16 in
  let maxd = if maxd < 6 then 6 else maxd in
  let t = make (maxd + 1) in
  Printf.printf "%d" (check t);
  let longlived = make maxd in
  let d = ref 4 in
  while !d <= maxd do
    let iters = 1 lsl (maxd - !d + 4) in
    let sum = ref 0 in
    for _ = 1 to iters do
      let a = make !d in
      sum := !sum + check a
    done;
    Printf.printf " %d" !sum;
    d := !d + 2
  done;
  Printf.printf " %d\n" (check longlived)
