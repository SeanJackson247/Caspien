(* Sieve of Eratosthenes: count the primes <= N (byte per number). *)
let () =
  let n = if Array.length Sys.argv > 1 then int_of_string Sys.argv.(1) else 100000000 in
  let f = Bytes.make (n + 1) '\001' in
  let i = ref 2 in
  while !i * !i <= n do
    if Bytes.unsafe_get f !i <> '\000' then begin
      let j = ref (!i * !i) in
      while !j <= n do
        Bytes.set f !j '\000';
        j := !j + !i
      done
    end;
    incr i
  done;
  let count = ref 0 in
  for i = 2 to n do
    count := !count + Char.code (Bytes.get f i)
  done;
  Printf.printf "%d\n" !count
