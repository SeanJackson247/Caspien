(* fannkuch-redux, single threaded: see fannkuchredux.c. *)
let fannkuchredux n =
  let perm = Array.make 16 0 and perm1 = Array.make 16 0 and count = Array.make 16 0 in
  let max_flips = ref 0 and perm_count = ref 0 and checksum = ref 0 in
  let r = ref n in
  for i = 0 to n - 1 do perm1.(i) <- i done;
  let result = ref (-1) in
  while !result < 0 do
    while !r <> 1 do count.(!r - 1) <- !r; decr r done;
    for i = 0 to n - 1 do perm.(i) <- perm1.(i) done;
    let flips = ref 0 in
    let k = ref perm.(0) in
    while !k <> 0 do
      let kk = !k in
      let k2 = (kk + 1) lsr 1 in
      for i = 0 to k2 - 1 do
        let t = perm.(i) in
        perm.(i) <- perm.(kk - i);
        perm.(kk - i) <- t
      done;
      incr flips;
      k := perm.(0)
    done;
    if !flips > !max_flips then max_flips := !flips;
    checksum := !checksum + (if !perm_count mod 2 = 0 then !flips else - !flips);
    let brk = ref false in
    while not !brk && !result < 0 do
      if !r = n then begin
        Printf.printf "%d\n" !checksum;
        result := !max_flips
      end else begin
        let perm0 = perm1.(0) in
        let i = ref 0 in
        while !i < !r do
          perm1.(!i) <- perm1.(!i + 1);
          incr i
        done;
        perm1.(!r) <- perm0;
        count.(!r) <- count.(!r) - 1;
        if count.(!r) > 0 then brk := true else incr r
      end
    done;
    incr perm_count
  done;
  !result

let () =
  let n = if Array.length Sys.argv > 1 then int_of_string Sys.argv.(1) else 7 in
  let f = fannkuchredux n in
  Printf.printf "Pfannkuchen(%d) = %d\n" n f
