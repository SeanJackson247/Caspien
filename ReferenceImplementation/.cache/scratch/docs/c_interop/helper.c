/* The C half of interop.caspien. `add` is defined by the Caspien program (see `export add`). */
unsigned long add(unsigned long a, unsigned long b);

unsigned long c_apply(unsigned long a, unsigned long b){
	return add(a, b) * 2;
}
