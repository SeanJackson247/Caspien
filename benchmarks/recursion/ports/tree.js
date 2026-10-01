function visit(d) { let t = d + 1; if (d > 0) { t += visit(d - 1); t += visit(d - 1); } return t; }
console.log(String(visit(+process.argv[2])));
