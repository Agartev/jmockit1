package mockit;

import java.applet.*;
import java.awt.*;

import org.junit.*;
import org.junit.rules.*;
import static org.junit.Assert.*;

public final class MisusedFakingAPITest
{
   @Rule public final ExpectedException thrown = ExpectedException.none();

   @Test
   public void fakeSameMethodTwiceWithReentrantFakesFromTwoDifferentFakeClasses() {
      new MockUp<Applet>() {
         @Mock
         int getComponentCount(Invocation inv) {
            int i = inv.proceed();
            return i + 1;
         }
      };

      int i = new Applet().getComponentCount();
      assertEquals(1, i);

      new MockUp<Applet>() {
         @Mock
         int getComponentCount(Invocation inv) {
            int j = inv.proceed();
            return j + 2;
         }
      };

      // Should return 3, but returns 5. Chaining mock methods is not supported.
      int j = new Applet().getComponentCount();
      assertEquals(5, j);
   }

   static final class AppletFake extends MockUp<Applet> {
      final int componentCount;
      AppletFake(int componentCount) { this.componentCount = componentCount; }
      @Mock int getComponentCount(Invocation inv) { return componentCount; }
   }

   @Test
   public void applyTheSameFakeForAClassTwice() {
      new AppletFake(1);
      new AppletFake(2); // second application overrides the previous one

      assertEquals(2, new Applet().getComponentCount());
   }

   @Test
   public void fakeAPrivateMethodOrConstructor() {
      // Still supported, but writes a warning to the console.
      new MockUp<Component>() {
         @Mock boolean checkCoalescing() { return false; }
      };

      new MockUp<System>() {
         @Mock void $init() {}
      };
   }
}