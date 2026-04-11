# Monomorphization (COOOL PA4)

**Compilation steps**
```
javac -cp src:lib/soot-4.6.0-jar-with-dependencies.jar src/Main.java -d build/optimizer

javac -d build/original/testN tests/TestN.java 
```
**Run the optimizer**
```
java -cp build/optimizer:lib/soot-4.6.0-jar-with-dependencies.jar Main build/optimized/testN build/original/testN TestN
```

**Benchmark both before and after**
```
java -Xint -cp build/original/testN TestN
java -Xint -cp build/optimized/testN TestN
```