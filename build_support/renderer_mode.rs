// Keep application-specific changes out of the upstream submodule. Fail rather
// than silently dropping the setting if its initialization changes upstream.
pub fn patch(source: &str) -> String {
    let start = "\tif (const char *ubershader = getenv(\"PARALLEL_RDP_UBERSHADER\"))";
    let end = "\tbool allow_small_types = true;";
    assert_eq!(
        source.matches(start).count(),
        1,
        "Review renderer mode overlay: start changed"
    );
    assert_eq!(
        source.matches(end).count(),
        1,
        "Review renderer mode overlay: end changed"
    );
    let begin = source.find(start).unwrap();
    let finish = source.find(end).unwrap();
    assert!(begin < finish, "Review renderer mode initialization order");
    let mut patched = source.to_owned();
    patched.replace_range(
        begin..finish,
        r#"
    const bool compatibility = gopher64_renderer_compatibility();
    caps.ubershader = compatibility;
    caps.force_sync = compatibility;
    bool allow_subgroup = !compatibility;
    LOGI("Renderer mode: %s\n", compatibility ? "Compatibility" : "Standard");

"#,
    );
    format!("extern \"C\" bool gopher64_renderer_compatibility();\n{patched}")
}
