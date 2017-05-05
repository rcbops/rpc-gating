ClassLoader gcl = new GroovyClassLoader()
for (scriptfile in args) {
  print("${scriptfile} ")
  String script = new File(scriptfile).text
  Class c = gcl.parseClass(script)
  print("[OK]\n")
}
