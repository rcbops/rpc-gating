// Dummy FlowInterruptedException only used in lint jobs.

package hudson

import java.io.IOException;

public class AbortException extends IOException{
  public FlowInterruptedException() {
  }
}
