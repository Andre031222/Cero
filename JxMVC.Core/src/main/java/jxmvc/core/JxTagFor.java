/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo
///  reborn on : 10/02/2026
///  dated on  : 19/09/2024
/// 

package jxmvc.core;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.tagext.TagSupport;
import java.util.Iterator;
import java.util.List;

public class JxTagFor extends TagSupport
{
    private List<?> items;
    private String var;
    private int counter = 0;
    private int maxCount = Integer.MAX_VALUE;
    private Iterator<?> iterator;

    public void setItems(List<?> items)
    {
        this.items = items;
    }

    public void setVar(String var)
    {
        this.var = var;
    }

    public void setMaxCount(int maxCount)
    {
        this.maxCount = maxCount;
    }

    @Override
    public int doStartTag() throws JspException
    {
        if (items != null && !items.isEmpty()) {
            iterator = items.iterator();
            counter = 1;
            return processNextItem();
        }
        return SKIP_BODY;
    }

    @Override
    public int doAfterBody() throws JspException
    {
        counter++;
        if (counter > maxCount || !iterator.hasNext())
            return SKIP_BODY;

        return processNextItem();
    }

    private int processNextItem()
    {
        if (iterator.hasNext()) {
            pageContext.setAttribute(var, iterator.next());
            pageContext.setAttribute("counter", counter);
            return EVAL_BODY_AGAIN;
        }
        return SKIP_BODY;
    }

    @Override
    public int doEndTag() throws JspException
    {
        pageContext.removeAttribute(var);
        pageContext.removeAttribute("counter");
        return EVAL_PAGE;
    }
}
