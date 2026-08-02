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
