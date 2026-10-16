(* spectral-norm, single threaded: see spectralnorm.c. *)
let eval_a i j = 1.0 /. float_of_int ((i + j) * (i + j + 1) / 2 + i + 1)

let a_times_u n (u : float array) (au : float array) =
  for i = 0 to n - 1 do
    let s = ref 0.0 in
    for j = 0 to n - 1 do s := !s +. eval_a i j *. u.(j) done;
    au.(i) <- !s
  done

let at_times_u n (u : float array) (au : float array) =
  for i = 0 to n - 1 do
    let s = ref 0.0 in
    for j = 0 to n - 1 do s := !s +. eval_a j i *. u.(j) done;
    au.(i) <- !s
  done

let ata_times_u n u atau =
  let v = Array.make n 0.0 in
  a_times_u n u v;
  at_times_u n v atau

let () =
  let n = if Array.length Sys.argv > 1 then int_of_string Sys.argv.(1) else 100 in
  let u = Array.make n 1.0 and v = Array.make n 0.0 in
  for _ = 1 to 10 do
    ata_times_u n u v;
    ata_times_u n v u
  done;
  let vbv = ref 0.0 and vv = ref 0.0 in
  for i = 0 to n - 1 do
    vbv := !vbv +. u.(i) *. v.(i);
    vv := !vv +. v.(i) *. v.(i)
  done;
  Printf.printf "%0.9f\n" (sqrt (!vbv /. !vv))
