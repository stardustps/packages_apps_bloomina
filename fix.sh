#!/bin/bash
sed -i 's/bindLinks(m) \/\//bindLinks(mt)/g' app/src/main/java/com/Zerodactyl/bloomina/ui/MaintainerFragment.kt
sed -i '/v.btnDonate.setOnClickListener/d' app/src/main/java/com/Zerodactyl/bloomina/ui/MaintainerFragment.kt
# Move bindLinks inside the class
sed -i '/^    private fun bindLinks/,/^    }/d' app/src/main/java/com/Zerodactyl/bloomina/ui/MaintainerFragment.kt
cat << 'INNER_EOF' >> app/src/main/java/com/Zerodactyl/bloomina/ui/MaintainerFragment.kt

    private fun bindLinks(m: com.Zerodactyl.bloomina.data.Maintainer) {
        val v = _b ?: return
        
        fun setupLink(btn: View, div: View?, url: String?) {
            if (!url.isNullOrBlank()) {
                btn.visibility = View.VISIBLE
                div?.visibility = View.VISIBLE
                btn.setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            } else {
                btn.visibility = View.GONE
                div?.visibility = View.GONE
            }
        }
        
        setupLink(v.btnTelegram, null, m.telegram)
        setupLink(v.btnDonate, null, m.donateUrl)
        setupLink(v.btnGithub, v.divGithub, m.githubUrl)
        setupLink(v.btnXda, v.divXda, m.xdaUrl)
    }
}
INNER_EOF
# Remove the extra closing brace from the old class end
sed -i '71d' app/src/main/java/com/Zerodactyl/bloomina/ui/MaintainerFragment.kt
