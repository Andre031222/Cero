package jxmvc.core;

import java.lang.reflect.Field;

public class SchedulerCronTest {

    static int passed = 0;
    static int failed = 0;

    static void ok(String l)            { passed++; System.out.println("  OK  " + l); }
    static void fail(String l, String m){ failed++; System.out.println("  FAIL " + l + ": " + m); }
    static void check(String l, boolean c){ if (c) ok(l); else fail(l, "false"); }

    public static void main(String[] args) throws Exception {
        testEveryMinute();
        testSpecificMinuteHour();
        testRange();
        testList();
        testStep();
        testDayOfMonth();
        testMonth();
        testDayOfWeek();
        testInvalidFieldCount();
        System.out.printf("SchedulerCronTest: pass=%d fail=%d%n", passed, failed);
    }

    static int[] field(JxScheduler.CronTrigger t, String name) throws Exception {
        Field f = JxScheduler.CronTrigger.class.getDeclaredField(name);
        f.setAccessible(true);
        return (int[]) f.get(t);
    }

    static boolean has(int[] arr, int val) {
        for (int a : arr) if (a == val) return true;
        return false;
    }

    static void testEveryMinute() throws Exception {
        JxScheduler.CronTrigger t = new JxScheduler.CronTrigger("* * * * *");
        int[] mins = field(t, "minutes");
        check("* mins count 60",  mins.length == 60);
        check("* has 0",  has(mins, 0));
        check("* has 59", has(mins, 59));
    }

    static void testSpecificMinuteHour() throws Exception {
        JxScheduler.CronTrigger t = new JxScheduler.CronTrigger("30 14 * * *");
        int[] mins  = field(t, "minutes");
        int[] hours = field(t, "hours");
        check("min 30 present",    has(mins,  30));
        check("min 0 absent",      !has(mins, 0));
        check("hour 14 present",   has(hours, 14));
        check("hour 15 absent",    !has(hours, 15));
    }

    static void testRange() throws Exception {
        JxScheduler.CronTrigger t = new JxScheduler.CronTrigger("10-20 * * * *");
        int[] mins = field(t, "minutes");
        check("range has 10",  has(mins, 10));
        check("range has 15",  has(mins, 15));
        check("range has 20",  has(mins, 20));
        check("range no 9",    !has(mins, 9));
        check("range no 21",   !has(mins, 21));
    }

    static void testList() throws Exception {
        JxScheduler.CronTrigger t = new JxScheduler.CronTrigger("0,15,30,45 * * * *");
        int[] mins = field(t, "minutes");
        check("list has 0",    has(mins, 0));
        check("list has 15",   has(mins, 15));
        check("list has 30",   has(mins, 30));
        check("list has 45",   has(mins, 45));
        check("list no 1",     !has(mins, 1));
    }

    static void testStep() throws Exception {
        JxScheduler.CronTrigger t = new JxScheduler.CronTrigger("*/15 * * * *");
        int[] mins = field(t, "minutes");
        check("step has 0",    has(mins, 0));
        check("step has 15",   has(mins, 15));
        check("step has 30",   has(mins, 30));
        check("step has 45",   has(mins, 45));
        check("step no 10",    !has(mins, 10));
    }

    static void testDayOfMonth() throws Exception {
        JxScheduler.CronTrigger t = new JxScheduler.CronTrigger("0 0 1 * *");
        int[] doms = field(t, "doms");
        check("dom has 1",  has(doms, 1));
        check("dom no 2",   !has(doms, 2));
    }

    static void testMonth() throws Exception {
        JxScheduler.CronTrigger t = new JxScheduler.CronTrigger("0 0 * 6 *");
        int[] months = field(t, "months");
        check("month has 6",  has(months, 6));
        check("month no 1",   !has(months, 1));
    }

    static void testDayOfWeek() throws Exception {
        JxScheduler.CronTrigger t = new JxScheduler.CronTrigger("0 9 * * 1");
        int[] dows = field(t, "dows");
        check("dow has 1 (lunes)", has(dows, 1));
        check("dow no 0 (domingo)", !has(dows, 0));
    }

    static void testInvalidFieldCount() {
        try {
            new JxScheduler.CronTrigger("* * * *");
            fail("4 campos lanza excepción", "no lanzó");
        } catch (IllegalArgumentException e) {
            ok("4 campos lanza IllegalArgumentException");
        }
    }
}
