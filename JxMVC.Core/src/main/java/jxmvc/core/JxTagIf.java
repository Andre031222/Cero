/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo
///  reborn on : 10/02/2026
///  dated on  : 19/09/2024
/// 

package jxmvc.core;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.tagext.TagSupport;

public class JxTagIf extends TagSupport
{
    private boolean test;

    public void setTest(boolean test)
    {
        this.test = test;
    }

    @Override
    public int doStartTag() throws JspException
    {
        return test ? EVAL_BODY_INCLUDE : SKIP_BODY;
    }
}
