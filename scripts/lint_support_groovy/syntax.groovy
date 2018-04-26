ClassLoader gcl = new GroovyClassLoader()
rc=0
for (scriptfile in args) {
  try{
    print("${scriptfile} ")
    String script = new File(scriptfile).text
    Class c = gcl.parseClass(script)
  } catch (e){
    print("[PARSE ERROR]\n${e}")
    rc=1
    continue
  }
  print("[OK]\n")
}
System.exit rc
