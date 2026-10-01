(* Mandelbrot escape-time benchmark: see mandelbrot.c. *)
let () =
  let n = if Array.length Sys.argv > 1 then int_of_string Sys.argv.(1) else 3000 in
  let dn = float_of_int n in
  let inside = ref 0 and total = ref 0 in
  for y = 0 to n - 1 do
    let ci = 2.0 *. float_of_int y /. dn -. 1.0 in
    for x = 0 to n - 1 do
      let cr = 2.0 *. float_of_int x /. dn -. 1.5 in
      let zr = ref 0.0 and zi = ref 0.0 and tr = ref 0.0 and ti = ref 0.0 in
      let i = ref 0 in
      while !i < 100 && !tr +. !ti <= 4.0 do
        zi := 2.0 *. !zr *. !zi +. ci;
        zr := !tr -. !ti +. cr;
        tr := !zr *. !zr;
        ti := !zi *. !zi;
        incr i
      done;
      total := !total + !i;
      if !i = 100 then incr inside
    done
  done;
  Printf.printf "%d %d\n" !inside !total
