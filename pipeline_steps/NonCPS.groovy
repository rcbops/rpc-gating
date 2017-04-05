// Dummy class so that lint checks dont fail due to @NonCPS not being available
// outside jenkins
@interface NonCPS{
}

class Main implements Runnable {
  void run(){
  }
}
