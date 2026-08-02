package jxmvc.core;

import java.util.List;
import java.util.Map;

public class ValidationExtTest {

    static int passed = 0;
    static int failed = 0;

    static void ok(String l)            { passed++; System.out.println("  OK  " + l); }
    static void fail(String l, String m){ failed++; System.out.println("  FAIL " + l + ": " + m); }
    static void check(String l, boolean c){ if (c) ok(l); else fail(l, "false"); }

    static List<String> validate(Object obj) {
        return JxValidation.check(obj);
    }

    public static void main(String[] args) {
        testRequired();
        testNotNull();
        testMinMaxLength();
        testLength();
        testMinMax();
        testRange();
        testEmail();
        testPhone();
        testPattern();
        testPositive();
        testNotEmpty();
        testIn();
        testDigits();
        testSafe();
        testUrl();
        System.out.printf("ValidationExtTest: pass=%d fail=%d%n", passed, failed);
    }

    static void testRequired() {
        check("JxRequired null fails",      !validate(new ReqBean(null)).isEmpty());
        check("JxRequired blank fails",     !validate(new ReqBean("")).isEmpty());
        check("JxRequired valid passes",    validate(new ReqBean("ok")).isEmpty());
    }

    static void testNotNull() {
        check("JxNotNull null fails",    !validate(new NnBean(null)).isEmpty());
        check("JxNotNull obj passes",    validate(new NnBean("x")).isEmpty());
    }

    static void testMinMaxLength() {
        check("JxMinLength short fails",  !validate(new MinLenBean("ab")).isEmpty());
        check("JxMinLength ok passes",    validate(new MinLenBean("abcde")).isEmpty());
        check("JxMaxLength long fails",   !validate(new MaxLenBean("abcdef")).isEmpty());
        check("JxMaxLength ok passes",    validate(new MaxLenBean("abc")).isEmpty());
    }

    static void testLength() {
        check("JxLength wrong fails", !validate(new LenBean("abcde")).isEmpty());
        check("JxLength exact passes", validate(new LenBean("abc")).isEmpty());
    }

    static void testMinMax() {
        check("JxMin below fails",  !validate(new MinBean(0)).isEmpty());
        check("JxMin ok passes",    validate(new MinBean(1)).isEmpty());
        check("JxMax above fails",  !validate(new MaxBean(101)).isEmpty());
        check("JxMax ok passes",    validate(new MaxBean(100)).isEmpty());
    }

    static void testRange() {
        check("JxRange below fails", !validate(new RangeBean(4)).isEmpty());
        check("JxRange above fails", !validate(new RangeBean(11)).isEmpty());
        check("JxRange ok passes",   validate(new RangeBean(5)).isEmpty());
    }

    static void testEmail() {
        check("JxEmail invalid fails", !validate(new EmailBean("notanemail")).isEmpty());
        check("JxEmail valid passes",  validate(new EmailBean("a@b.com")).isEmpty());
    }

    static void testPhone() {
        check("JxPhone invalid fails", !validate(new PhoneBean("letters")).isEmpty());
        check("JxPhone valid passes",  validate(new PhoneBean("+51999123456")).isEmpty());
    }

    static void testPattern() {
        check("JxPattern no match fails", !validate(new PatBean("abc")).isEmpty());
        check("JxPattern match passes",   validate(new PatBean("123")).isEmpty());
    }

    static void testPositive() {
        check("JxPositive zero fails",    !validate(new PosBean(0)).isEmpty());
        check("JxPositive negative fails",!validate(new PosBean(-1)).isEmpty());
        check("JxPositive ok passes",     validate(new PosBean(1)).isEmpty());
    }

    static void testNotEmpty() {
        check("JxNotEmpty empty fails",  !validate(new NemBean("")).isEmpty());
        check("JxNotEmpty blank fails",  !validate(new NemBean("  ")).isEmpty());
        check("JxNotEmpty ok passes",    validate(new NemBean("x")).isEmpty());
    }

    static void testIn() {
        check("JxIn not in list fails", !validate(new InBean("d")).isEmpty());
        check("JxIn in list passes",    validate(new InBean("a")).isEmpty());
    }

    static void testDigits() {
        check("JxDigits wrong count fails", !validate(new DigBean("1234")).isEmpty());
        check("JxDigits exact passes",      validate(new DigBean("12345")).isEmpty());
        check("JxDigits non-digit fails",   !validate(new DigBean("1234a")).isEmpty());
    }

    static void testSafe() {
        check("JxSafe script fails",  !validate(new SafeBean("<script>")).isEmpty());
        check("JxSafe normal passes", validate(new SafeBean("hello")).isEmpty());
    }

    static void testUrl() {
        check("JxUrl invalid fails", !validate(new UrlBean("not-a-url")).isEmpty());
        check("JxUrl http passes",   validate(new UrlBean("http://example.com")).isEmpty());
        check("JxUrl https passes",  validate(new UrlBean("https://example.com/path")).isEmpty());
    }

    // --- Bean helpers ---

    static class ReqBean  { @JxValidation.JxRequired String v; ReqBean(String v){this.v=v;} }
    static class NnBean   { @JxValidation.JxNotNull  Object v; NnBean(Object v){this.v=v;} }
    static class MinLenBean{ @JxValidation.JxMinLength(5) String v; MinLenBean(String v){this.v=v;} }
    static class MaxLenBean{ @JxValidation.JxMaxLength(5) String v; MaxLenBean(String v){this.v=v;} }
    static class LenBean  { @JxValidation.JxLength(3) String v;    LenBean(String v){this.v=v;} }
    static class MinBean  { @JxValidation.JxMin(1) double v;       MinBean(double v){this.v=v;} }
    static class MaxBean  { @JxValidation.JxMax(100) double v;     MaxBean(double v){this.v=v;} }
    static class RangeBean{ @JxValidation.JxRange(min=5,max=10) double v; RangeBean(double v){this.v=v;} }
    static class EmailBean{ @JxValidation.JxEmail String v;        EmailBean(String v){this.v=v;} }
    static class PhoneBean{ @JxValidation.JxPhone String v;        PhoneBean(String v){this.v=v;} }
    static class PatBean  { @JxValidation.JxPattern("\\d+") String v; PatBean(String v){this.v=v;} }
    static class PosBean  { @JxValidation.JxPositive double v;     PosBean(double v){this.v=v;} }
    static class NemBean  { @JxValidation.JxNotEmpty String v;     NemBean(String v){this.v=v;} }
    static class InBean   { @JxValidation.JxIn({"a","b","c"}) String v; InBean(String v){this.v=v;} }
    static class DigBean  { @JxValidation.JxDigits(5) String v;    DigBean(String v){this.v=v;} }
    static class SafeBean { @JxValidation.JxSafe String v;         SafeBean(String v){this.v=v;} }
    static class UrlBean  { @JxValidation.JxUrl String v;          UrlBean(String v){this.v=v;} }
}
