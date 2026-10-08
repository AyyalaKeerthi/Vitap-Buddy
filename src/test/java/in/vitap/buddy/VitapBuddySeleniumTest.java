package in.vitap.buddy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;

import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

public class VitapBuddySeleniumTest {

    private WebDriver driver;
    private WebDriverWait wait;

    private final String BASE_URL = "http://localhost:8090";

    // Used only so we can visually watch Selenium
    private void pause(int milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @BeforeEach
    void setUp() {

        driver = new ChromeDriver();

        wait = new WebDriverWait(
                driver,
                Duration.ofSeconds(10)
        );

        driver.manage().window().maximize();

        // Open VIT-AP Buddy
        driver.get(BASE_URL);

        // Give the page some time to visually load
        pause(3000);
    }

    @Test
    void testPageLoads() {

        // Wait for the page heading
        WebElement heading = wait.until(
                ExpectedConditions.visibilityOfElementLocated(
                        By.tagName("h1")
                )
        );

        // Verify page title
        assertEquals(
                "VIT-AP Buddy",
                driver.getTitle()
        );

        // Verify heading
        assertEquals(
                "VIT-AP Buddy",
                heading.getText()
        );

        pause(3000);

        // Verify question input exists
        WebElement input = wait.until(
                ExpectedConditions.visibilityOfElementLocated(
                        By.id("q")
                )
        );

        assertTrue(input.isDisplayed());

        pause(2000);

        // Verify Ask button exists
        WebElement askButton = wait.until(
                ExpectedConditions.visibilityOfElementLocated(
                        By.cssSelector("button.send")
                )
        );

        assertTrue(askButton.isDisplayed());

        pause(3000);
    }


    @Test
    void testProfileSelection() {

        pause(2000);

        // Find Boys Hostel button
        WebElement boysHostel = wait.until(
                ExpectedConditions.elementToBeClickable(
                        By.cssSelector(
                                "button.pill[data-v='boys hostel']"
                        )
                )
        );

        pause(2000);

        // Click Boys Hostel
        boysHostel.click();

        pause(2000);

        // Verify selected
        assertEquals(
                "true",
                boysHostel.getAttribute("aria-pressed")
        );

        pause(3000);
    }


    @Test
    void testQuestionInput() {

        WebElement input = wait.until(
                ExpectedConditions.visibilityOfElementLocated(
                        By.id("q")
                )
        );

        pause(2000);

        // Type question
        input.sendKeys("What is VTOP?");

        pause(3000);

        // Verify entered text
        assertEquals(
                "What is VTOP?",
                input.getAttribute("value")
        );

        pause(3000);
    }


    @Test
    void testStarterQuestion() {

        pause(2000);

        // Find first starter question
        WebElement starter = wait.until(
                ExpectedConditions.elementToBeClickable(
                        By.cssSelector(".chip")
                )
        );

        pause(2000);

        // Click starter question
        starter.click();

        pause(3000);

        // Wait until starter section disappears
        wait.until(
                ExpectedConditions.invisibilityOfElementLocated(
                        By.id("starters")
                )
        );

        pause(2000);

        // Verify chat contains something
        WebElement chat = wait.until(
                ExpectedConditions.visibilityOfElementLocated(
                        By.id("chat")
                )
        );

        assertTrue(
                chat.getText().length() > 0
        );

        pause(3000);
    }


    @Test
    void testAskQuestion() {

        // Find input
        WebElement input = wait.until(
                ExpectedConditions.visibilityOfElementLocated(
                        By.id("q")
                )
        );

        pause(2000);

        // Type question
        input.sendKeys("What is VTOP?");

        pause(3000);

        // Find Ask button
        WebElement askButton = wait.until(
                ExpectedConditions.elementToBeClickable(
                        By.cssSelector("button.send")
                )
        );

        pause(2000);

        // Click Ask
        askButton.click();

        pause(5000);

        // Wait for user question to appear in chat
        wait.until(
                ExpectedConditions.textToBePresentInElementLocated(
                        By.id("chat"),
                        "What is VTOP?"
                )
        );

        pause(3000);

        // Get chat
        WebElement chat = driver.findElement(
                By.id("chat")
        );

        // Verify question appeared
        assertTrue(
                chat.getText().contains("What is VTOP?")
        );

        pause(5000);
    }


    @AfterEach
    void tearDown() {

        // Keep browser visible before closing
        pause(3000);

        if (driver != null) {
            driver.quit();
        }
    }
}