const buttons = [...document.querySelectorAll("button")];
const status = document.getElementById("status");
let selected = 0;

const launches = Number(localStorage.getItem("launches") || 0) + 1;
localStorage.setItem("launches", String(launches));
document.getElementById("launches").textContent = launches;

function focusButton(index) {
  selected = (index + buttons.length) % buttons.length;
  buttons.forEach((button, position) => button.classList.toggle("focused", position === selected));
}

async function activate(action) {
  try {
    if (action === "battery") {
      const battery = await navigator.kaiRuntime.getBattery();
      status.textContent = `Battery: ${Math.round(battery.level * 100)}%`;
    } else if (action === "vibrate") {
      await navigator.kaiRuntime.vibrate(180);
      status.textContent = "Vibration requested";
    } else if (action === "location") {
      const location = await navigator.kaiRuntime.getLocation();
      status.textContent = `${location.latitude.toFixed(3)}, ${location.longitude.toFixed(3)}`;
    } else if (action === "notify") {
      await navigator.kaiRuntime.showNotification("Hello KaiOS", "The Android bridge is working.");
      status.textContent = "Notification shown";
    }
  } catch (error) {
    status.textContent = error.message;
  }
}

document.addEventListener("keydown", event => {
  if (event.key === "ArrowLeft" || event.key === "ArrowUp") focusButton(selected - 1);
  if (event.key === "ArrowRight" || event.key === "ArrowDown") focusButton(selected + 1);
  if (event.key === "Enter") activate(buttons[selected].dataset.action);
  if (event.key === "SoftLeft") activate("vibrate");
  if (event.key === "SoftRight") activate("battery");
});

buttons.forEach(button => button.addEventListener("click", () => activate(button.dataset.action)));
