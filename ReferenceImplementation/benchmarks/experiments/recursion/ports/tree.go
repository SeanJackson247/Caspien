package main
import ("fmt";"os";"strconv")
func visit(d uint64) uint64 { t := d + 1; if d > 0 { t += visit(d - 1); t += visit(d - 1) }; return t }
func main() { d, _ := strconv.ParseUint(os.Args[1], 10, 64); fmt.Println(visit(d)) }
