package jxmvc.bench;

import jxmvc.core.ActionResult;
import jxmvc.core.JxController;
import jxmvc.core.JxMapping.*;

@JxControllerMain
@JxControllerMapping("bench")
public class BenchController extends JxController {

    @JxGetMapping("/plaintext")
    public ActionResult plaintext() { return text("OK"); }

    @JxGetMapping("/json")
    public ActionResult json() { return json("{\"message\":\"hello\",\"n\":42}"); }

    // El método NO puede llamarse db(): choca con JxController.db() (accessor JxDB).
    @JxGetMapping("/db")
    public ActionResult dbRow() { return json(Db.json()); }
}
