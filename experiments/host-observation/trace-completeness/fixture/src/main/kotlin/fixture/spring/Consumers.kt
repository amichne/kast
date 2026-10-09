package fixture.spring
data class Article(val slug: String)
data class User(val login: String)
fun repositoryConsumer(repo: ArticleRepository): Article? = repo.findBySlug("source")
