use crate::ui::{
    self,
    config::{Config, InputControllerAxis, InputItem, InputKeyButton},
};
use slint::{ComponentHandle, Model};
use std::{cell::RefCell, rc::Rc};

const LABELS: [&str; 19] = [
    "D-pad Right",
    "D-pad Left",
    "D-pad Down",
    "D-pad Up",
    "Start",
    "Z",
    "B",
    "A",
    "C Right",
    "C Left",
    "C Down",
    "C Up",
    "R",
    "L",
    "Stick Right",
    "Stick Left",
    "Stick Down",
    "Stick Up",
    "Hotkey",
];

// Copy shared legacy bindings on first edit. Never overwrite another player's
// mappings or delete an existing named profile during migration.
fn editable_name(config: &mut Config, port: usize) -> String {
    let current = config.input.input_profile_binding[port].clone();
    if current.starts_with("__direct_controller_")
        && config.input.input_profiles.contains_key(&current)
        && config
            .input
            .input_profile_binding
            .iter()
            .filter(|p| **p == current)
            .count()
            == 1
    {
        return current;
    }
    let copy = config
        .input
        .input_profiles
        .get(&current)
        .cloned()
        .unwrap_or_else(ui::input_profile::get_default_profile);
    let mut suffix = 0;
    let name = loop {
        let name = format!("__direct_controller_{}_{}", port + 1, suffix);
        if !config.input.input_profiles.contains_key(&name) {
            break name;
        }
        suffix += 1;
    };
    config.input.input_profiles.insert(name.clone(), copy);
    config.input.input_profile_binding[port] = name.clone();
    name
}

fn choices() -> Vec<(String, Option<InputItem>)> {
    use sdl3_sys::gamepad::*;
    let mut result = vec![("Unmapped".into(), None)];
    for (label, id) in [
        ("South (A / Cross)", SDL_GAMEPAD_BUTTON_SOUTH),
        ("East (B / Circle)", SDL_GAMEPAD_BUTTON_EAST),
        ("West (X / Square)", SDL_GAMEPAD_BUTTON_WEST),
        ("North (Y / Triangle)", SDL_GAMEPAD_BUTTON_NORTH),
        ("Back / Select", SDL_GAMEPAD_BUTTON_BACK),
        ("Start", SDL_GAMEPAD_BUTTON_START),
        ("Left stick click", SDL_GAMEPAD_BUTTON_LEFT_STICK),
        ("Right stick click", SDL_GAMEPAD_BUTTON_RIGHT_STICK),
        ("Left shoulder", SDL_GAMEPAD_BUTTON_LEFT_SHOULDER),
        ("Right shoulder", SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER),
        ("D-pad Up", SDL_GAMEPAD_BUTTON_DPAD_UP),
        ("D-pad Down", SDL_GAMEPAD_BUTTON_DPAD_DOWN),
        ("D-pad Left", SDL_GAMEPAD_BUTTON_DPAD_LEFT),
        ("D-pad Right", SDL_GAMEPAD_BUTTON_DPAD_RIGHT),
    ] {
        result.push((
            label.into(),
            Some(InputItem::ControllerButton(InputKeyButton {
                id: i32::from(id),
            })),
        ));
    }
    for (label, id, direction) in [
        ("Left stick Left", SDL_GAMEPAD_AXIS_LEFTX, -1),
        ("Left stick Right", SDL_GAMEPAD_AXIS_LEFTX, 1),
        ("Left stick Up", SDL_GAMEPAD_AXIS_LEFTY, -1),
        ("Left stick Down", SDL_GAMEPAD_AXIS_LEFTY, 1),
        ("Right stick Left", SDL_GAMEPAD_AXIS_RIGHTX, -1),
        ("Right stick Right", SDL_GAMEPAD_AXIS_RIGHTX, 1),
        ("Right stick Up", SDL_GAMEPAD_AXIS_RIGHTY, -1),
        ("Right stick Down", SDL_GAMEPAD_AXIS_RIGHTY, 1),
        ("Left trigger", SDL_GAMEPAD_AXIS_LEFT_TRIGGER, 1),
        ("Right trigger", SDL_GAMEPAD_AXIS_RIGHT_TRIGGER, 1),
    ] {
        result.push((
            label.into(),
            Some(InputItem::ControllerAxis(InputControllerAxis {
                id: i32::from(id),
                axis: direction,
                initial_state: 0,
            })),
        ));
    }
    result
}

fn identity(item: &Option<InputItem>) -> String {
    serde_json::to_string(item).unwrap()
}

fn legacy_label(item: &Option<InputItem>) -> String {
    match item {
        Some(InputItem::Key(key)) => format!("Keyboard scan code {}", key.id),
        Some(InputItem::JoystickButton(key)) => format!("Joystick button {}", key.id),
        Some(InputItem::JoystickAxis(axis)) => format!("Joystick axis {} ({})", axis.id, axis.axis),
        Some(InputItem::JoystickHat(hat)) => format!("Joystick hat {} ({})", hat.id, hat.direction),
        _ => format!("Existing binding: {}", identity(item)),
    }
}

type Options = Rc<RefCell<Vec<(String, Option<InputItem>)>>>;

#[cfg(test)]
mod tests {
    use super::*;

    fn shared() -> Config {
        let mut config = Config::default();
        let mut profile = ui::input_profile::get_default_profile();
        profile.deadzone = 23;
        config.input.input_profiles.insert("shared".into(), profile);
        config.input.input_profile_binding = std::array::from_fn(|_| "shared".into());
        config
    }

    #[test]
    fn edits_copy_shared_mapping_without_changing_other_players() {
        let mut config = shared();
        let name = editable_name(&mut config, 0);
        assert_eq!(config.input.input_profiles[&name].deadzone, 23);
        config.input.input_profiles.get_mut(&name).unwrap().deadzone = 8;
        assert_eq!(config.input.input_profiles["shared"].deadzone, 23);
        assert_eq!(config.input.input_profile_binding[1], "shared");
        assert_eq!(editable_name(&mut config, 0), name);
    }

    #[test]
    fn migration_does_not_overwrite_an_existing_name() {
        let mut config = shared();
        config.input.input_profiles.insert(
            "__direct_controller_1_0".into(),
            ui::input_profile::get_default_profile(),
        );
        assert_eq!(editable_name(&mut config, 0), "__direct_controller_1_1");
    }

    #[test]
    fn shared_direct_mapping_is_also_copied() {
        let mut config = shared();
        let first = editable_name(&mut config, 0);
        config.input.input_profile_binding[1] = first.clone();
        let second = editable_name(&mut config, 1);
        assert_ne!(first, second);
        assert_eq!(config.input.input_profile_binding[0], first);
    }
}

fn refresh(app: &ui::gui::AppWindow, config: &Config, options: &Options) {
    let port = app.get_mapping_port().clamp(0, 3) as usize;
    let fallback = ui::input_profile::get_default_profile();
    let profile = config
        .input
        .input_profiles
        .get(&config.input.input_profile_binding[port])
        .unwrap_or(&fallback);
    let mut items = choices();
    for input in profile.inputs.iter().flatten() {
        if !items
            .iter()
            .any(|(_, item)| identity(item) == identity(input))
        {
            items.push((legacy_label(input), input.clone()));
        }
    }
    app.set_mapping_choices(
        Rc::new(slint::VecModel::from(
            items
                .iter()
                .map(|(name, _)| name.into())
                .collect::<Vec<slint::SharedString>>(),
        ))
        .into(),
    );
    app.set_mapping_rows(
        Rc::new(slint::VecModel::from(
            profile
                .inputs
                .iter()
                .enumerate()
                .map(|(index, row)| ui::gui::ControlMapping {
                    label: LABELS[index].into(),
                    primary: items
                        .iter()
                        .position(|(_, item)| identity(item) == identity(&row[1]))
                        .unwrap() as i32,
                    alternate: items
                        .iter()
                        .position(|(_, item)| identity(item) == identity(&row[0]))
                        .unwrap() as i32,
                })
                .collect::<Vec<_>>(),
        ))
        .into(),
    );
    app.set_mapping_deadzone(profile.deadzone);
    app.set_mapping_legacy(profile.dinput);
    *options.borrow_mut() = items;
    // Keep the legacy on-disk representation synchronized without exposing its
    // profile picker. Runtime input handling continues to read these bindings.
    let names: Vec<String> = config.input.input_profiles.keys().cloned().collect();
    app.set_input_profiles(
        Rc::new(slint::VecModel::from(
            names
                .iter()
                .map(|n| n.into())
                .collect::<Vec<slint::SharedString>>(),
        ))
        .into(),
    );
    app.set_selected_profile_binding(
        Rc::new(slint::VecModel::from(
            config
                .input
                .input_profile_binding
                .iter()
                .map(|name| names.iter().position(|n| n == name).unwrap_or(0) as i32)
                .collect::<Vec<_>>(),
        ))
        .into(),
    );
}

pub fn setup(app: &ui::gui::AppWindow, config: &Config) {
    let options: Options = Rc::new(RefCell::new(Vec::new()));
    refresh(app, config, &options);
    let weak = app.as_weak();
    let saved_options = options.clone();
    app.on_mapping_port_changed(move |_| {
        if let Some(app) = weak.upgrade() {
            refresh(&app, &Config::new(), &saved_options);
            app.set_mapping_status("".into());
        }
    });
    let weak = app.as_weak();
    let saved_options = options.clone();
    app.on_mapping_changed(move |row, slot, selection| {
        if let Some(app) = weak.upgrade() {
            if app.get_game_running() || !(0..19).contains(&row) || !(0..2).contains(&slot) {
                return;
            }
            let Some((_, input)) = saved_options.borrow().get(selection as usize).cloned() else {
                return;
            };
            ui::gui::save_settings(&app);
            let mut config = Config::new();
            let port = app.get_mapping_port().clamp(0, 3) as usize;
            let name = editable_name(&mut config, port);
            config.input.input_profiles.get_mut(&name).unwrap().inputs[row as usize]
                [slot as usize] = input;
            refresh(&app, &config, &saved_options);
            app.set_mapping_status("Mapping saved. Applies next game launch.".into());
        }
    });
    let weak = app.as_weak();
    let saved_options = options.clone();
    app.on_mapping_deadzone_changed(move |value| {
        if let Some(app) = weak.upgrade() {
            if app.get_game_running() {
                return;
            }
            ui::gui::save_settings(&app);
            let mut config = Config::new();
            let port = app.get_mapping_port().clamp(0, 3) as usize;
            let name = editable_name(&mut config, port);
            config.input.input_profiles.get_mut(&name).unwrap().deadzone = value.clamp(0, 99);
            refresh(&app, &config, &saved_options);
        }
    });
    let weak = app.as_weak();
    app.on_auto_map_controller(move || {
        if let Some(app) = weak.upgrade() {
            if app.get_game_running() { return; }
            let port = app.get_mapping_port().clamp(0, 3) as usize;
            let selected = app.get_selected_controller();
            if selected.row_data(port).unwrap_or(0) <= 0 {
                let paths = app.get_controller_paths();
                let free = (1..paths.row_count()).find(|index| !selected.iter().any(|p| p == *index as i32));
                let Some(index) = free else {
                    app.set_mapping_status("Connect a controller and select it under Controller Assignment.".into());
                    return;
                };
                selected.set_row_data(port, index as i32);
            }
            app.get_controller_changed().set_row_data(port, true);
            app.get_controller_enabled().set_row_data(port, true);
            ui::gui::save_settings(&app);
            let mut config = Config::new();
            let name = editable_name(&mut config, port);
            config.input.input_profiles.insert(name, ui::input_profile::get_default_profile());
            refresh(&app, &config, &options);
            app.set_mapping_status("Standard gamepad layout applied and controller assigned. Adjust any binding below.".into());
        }
    });
}
