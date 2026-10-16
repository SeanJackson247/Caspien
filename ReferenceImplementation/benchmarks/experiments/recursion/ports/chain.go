package main
import ("fmt";"os";"strconv")
func chain(acc, i, n uint64) uint64 { if i >= n { return acc }; return chain((acc*31)^i, i+1, n) }
func chainLoop(acc, i, n uint64) uint64 { for ; i < n; i++ { acc = (acc*31)^i }; return acc }
func main() {
	depth, _ := strconv.ParseUint(os.Args[1], 10, 64); reps, _ := strconv.ParseUint(os.Args[2], 10, 64)
	lp := len(os.Args) > 3 && os.Args[3] == "loop"; var total uint64
	for r := uint64(0); r < reps; r++ { if lp { total ^= chainLoop(r, 0, depth) } else { total ^= chain(r, 0, depth) } }
	fmt.Println(total)
}
