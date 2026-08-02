/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo

package jxmvc.core;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;

record BaseDispatchPlan(
        String controller,
        String action,
        String[] args,
        Map<String, String> pathVars,
        Class<? extends JxController> controllerClass,
        Method actionMethod
) {
    /** Constructor de compatibilidad para rutas sin variables de plantilla. */
    BaseDispatchPlan(String controller, String action, String[] args,
                     Class<? extends JxController> controllerClass, Method actionMethod) {
        this(controller, action, args, Collections.emptyMap(), controllerClass, actionMethod);
    }
}
